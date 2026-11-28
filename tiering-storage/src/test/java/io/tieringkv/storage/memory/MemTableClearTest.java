package io.tieringkv.storage.memory;

import io.tieringkv.storage.MutableClock;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Wave 3C (K1) MemTable O(256) clear() 专项测试：
 * 验证 live/TTL/tombstone 全量清空、MemoryManager 精确归还（含 MemTableManager 共享场景）、
 * 以及单调 Version 绝不回退（保障 WATCH/EXEC 与 TTLManager 守卫语义）。
 */
class MemTableClearTest {

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void clearResetsLiveTtlAndTombstonesWithExactMemoryAccountingAndMonotonicVersion() {
        MutableClock clock = new MutableClock(1_000L);
        MemoryManager memory = new MemoryManager(64L << 20);
        MemTable mem = MemTable.createForTest(clock, memory);

        for (int i = 0; i < 500; i++) {
            mem.put(bytes("k-" + i), bytes("val-" + i));
        }
        mem.put(bytes("ttl-key"), bytes("expiring"), 5_000L);
        mem.delete(bytes("k-10"));
        mem.putTombstone(bytes("ghost-tomb"));
        long preClearVersion = mem.versionOf(bytes("k-20"));
        assertThat(preClearVersion).isPositive();
        assertThat(mem.hasTombstone(bytes("k-10"))).isTrue();
        assertThat(mem.hasTombstone(bytes("ghost-tomb"))).isTrue();
        assertThat(memory.usedBytes()).isPositive();

        mem.clear();

        assertThat(mem.size()).isZero();
        assertThat(memory.usedBytes()).isZero();
        assertThat(mem.hasTombstone(bytes("k-10"))).isFalse();
        assertThat(mem.hasTombstone(bytes("ghost-tomb"))).isFalse();
        assertThat(mem.getEntry(bytes("k-10"))).isNull();
        assertThat(mem.versionOf(bytes("k-20"))).isZero();

        // 重写同名键：版本号必须严格大于清空前版本，确保 WATCH/EXEC 能检测到清空变更
        mem.put(bytes("k-20"), bytes("new-val"));
        assertThat(mem.versionOf(bytes("k-20"))).isGreaterThan(preClearVersion);
        assertThat(mem.size()).isEqualTo(1L);

        // 旧 TTL 任务到期触发 activeExpire 不得误删清空后新建的同名键
        mem.put(bytes("ttl-key"), bytes("fresh-no-ttl"));
        clock.advance(10_000L);
        assertThat(mem.activeExpire()).isZero();
        assertThat(mem.get(bytes("ttl-key"))).isEqualTo(bytes("fresh-no-ttl"));
    }

    @Test
    void clearOnSingleMemTableOnlyReclaimsOwnBytesFromSharedMemoryManager() {
        MemoryManager shared = new MemoryManager(64L << 20);
        MemTable t1 = MemTable.createForTest(new MutableClock(0L), shared);
        MemTable t2 = MemTable.createForTest(new MutableClock(0L), shared);

        for (int i = 0; i < 50; i++) {
            t1.put(bytes("a-" + i), bytes("va-" + i));
        }
        long bytesAfterT1 = shared.usedBytes();
        for (int i = 0; i < 50; i++) {
            t2.put(bytes("b-" + i), bytes("vb-" + i));
        }
        assertThat(shared.usedBytes()).isGreaterThan(bytesAfterT1);

        t2.clear();
        assertThat(t2.size()).isZero();
        assertThat(shared.usedBytes()).isEqualTo(bytesAfterT1);

        t1.clear();
        assertThat(t1.size()).isZero();
        assertThat(shared.usedBytes()).isZero();
    }
}
