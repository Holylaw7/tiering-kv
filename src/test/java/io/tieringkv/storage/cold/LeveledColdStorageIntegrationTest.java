package io.tieringkv.storage.cold;

import io.tieringkv.storage.memory.KeyValueEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Leveled compaction 冷层集成（ADR-0323）：合并后读一致 + level 提升。 */
class LeveledColdStorageIntegrationTest {

    @TempDir
    Path dir;

    private static List<KeyValueEntry> entries(String... kv) {
        List<KeyValueEntry> list = new ArrayList<>();
        for (int i = 0; i < kv.length; i += 2) {
            list.add(KeyValueEntry.live(
                    kv[i].getBytes(StandardCharsets.UTF_8),
                    kv[i + 1].getBytes(StandardCharsets.UTF_8),
                    0, -1, i / 2 + 1));
        }
        return list;
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    void l0MergeProducesL1AndReadsConsistent() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        LeveledCompaction leveled = new LeveledCompaction(2, 10);

        SSTableMeta t1 = cold.writeTable(entries(
                "k1", "v1-old", "k2", "v2"));
        leveled.onTableWritten(t1);
        SSTableMeta t2 = cold.writeTable(entries(
                "k1", "v1-new", "k3", "v3"));
        leveled.onTableWritten(t2);
        // L0=2 未超阈值 2（>2 触发）→ 再加一张
        SSTableMeta t3 = cold.writeTable(entries(
                "k4", "v4"));
        leveled.onTableWritten(t3);
        assertThat(leveled.nextMergeLevel()).isZero();

        SSTableMeta output = cold.compactLeveled(leveled);
        assertThat(output).isNotNull();
        assertThat(leveled.tablesAt(0)).isEmpty();
        assertThat(leveled.tablesAt(1)).hasSize(1);
        assertThat(leveled.maxLevel()).isEqualTo(1);
        // 读一致：latest wins + 全部键可读
        assertThat(cold.get(bytes("k1"))).isEqualTo(bytes("v1-new"));
        assertThat(cold.get(bytes("k2"))).isEqualTo(bytes("v2"));
        assertThat(cold.get(bytes("k3"))).isEqualTo(bytes("v3"));
        assertThat(cold.get(bytes("k4"))).isEqualTo(bytes("v4"));
        // 表数：3 输入 → 1 输出（L1 含输出）
        assertThat(leveled.tableCount()).isEqualTo(1);
    }

    @Test
    void tombstoneAndTtlSurviveLeveledMerge() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        LeveledCompaction leveled = new LeveledCompaction(2, 10);

        SSTableMeta t1 = cold.writeTable(List.of(KeyValueEntry.live(
                bytes("k1"), bytes("v1"), 0, -1, 1)));
        leveled.onTableWritten(t1);
        SSTableMeta t2 = cold.writeTable(List.of(KeyValueEntry.tombstone(
                bytes("k1"), 1, 1)));
        leveled.onTableWritten(t2);
        SSTableMeta t3 = cold.writeTable(List.of(KeyValueEntry.live(
                bytes("k2"), bytes("v2"), 0, -1, 2)));
        leveled.onTableWritten(t3);

        cold.compactLeveled(leveled);
        assertThat(cold.get(bytes("k1"))).isNull(); // tombstone 胜
        assertThat(cold.get(bytes("k2"))).isEqualTo(bytes("v2"));
    }

    @Test
    void noMergeWhenWithinLimits() throws Exception {
        ColdStorageEngine cold = new ColdStorageEngine(
                ColdStorageEngine.Config.defaults(dir));
        LeveledCompaction leveled = new LeveledCompaction(4, 10);
        SSTableMeta t1 = cold.writeTable(entries("k1", "v1"));
        leveled.onTableWritten(t1);
        SSTableMeta t2 = cold.writeTable(entries("k2", "v2"));
        leveled.onTableWritten(t2);
        assertThat(leveled.nextMergeLevel()).isEqualTo(-1);
        assertThat(cold.compactLeveled(leveled)).isNull();
    }
}
