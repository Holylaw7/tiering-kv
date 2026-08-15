package io.tieringkv.storage.cold;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Leveled compaction 策略（ADR-0323）：触发 / 读顺序 / 提升。 */
class LeveledCompactionTest {

    private static SSTableMeta meta(long id) {
        byte[] key = ("k" + id).getBytes(StandardCharsets.UTF_8);
        return new SSTableMeta(id, String.format("%08d.sst", id),
                1, 100, key, key);
    }

    @Test
    void newTablesEnterLevelZero() {
        LeveledCompaction leveled = new LeveledCompaction();
        leveled.onTableWritten(meta(1));
        leveled.onTableWritten(meta(2));
        assertThat(leveled.tablesAt(0)).hasSize(2);
        assertThat(leveled.maxLevel()).isZero();
        assertThat(leveled.nextMergeLevel()).isEqualTo(-1);
    }

    @ParameterizedTest(name = "tables {0}")
    @ValueSource(ints = {5, 6, 10})
    void l0ThresholdTriggersMerge(int tables) {
        LeveledCompaction leveled = new LeveledCompaction(4, 10);
        for (long i = 1; i <= tables; i++) {
            leveled.onTableWritten(meta(i));
        }
        assertThat(leveled.nextMergeLevel()).isZero();
    }

    @Test
    void higherLevelCapacityTriggersMerge() {
        LeveledCompaction leveled = new LeveledCompaction(4, 1);
        // 填满 L0 → 提升到 L1（两次合并形成 L1 两张表，超过容量 1）
        for (long i = 1; i <= 4; i++) {
            leveled.onTableWritten(meta(i));
        }
        leveled.promote(0, meta(100));
        for (long i = 5; i <= 8; i++) {
            leveled.onTableWritten(meta(i));
        }
        leveled.promote(0, meta(101)); // L1 = [100,101] > cap(1)
        assertThat(leveled.tablesAt(1)).hasSize(2);
        assertThat(leveled.nextMergeLevel()).isEqualTo(1);
    }

    @Test
    void readOrderL0NewFirstThenLevelsAscending() {
        LeveledCompaction leveled = new LeveledCompaction();
        leveled.onTableWritten(meta(1));
        leveled.onTableWritten(meta(2));
        leveled.promote(0, meta(10)); // L1
        leveled.onTableWritten(meta(3));
        List<SSTableMeta> order = leveled.orderForRead();
        // promote 清空 L0；随后 meta(3) 进 L0；顺序 [3, 10]
        assertThat(order.get(0).id()).isEqualTo(3);
        assertThat(order.get(1).id()).isEqualTo(10);
    }

    @Test
    void promoteMovesOutputToNextLevel() {
        LeveledCompaction leveled = new LeveledCompaction(2, 10);
        leveled.onTableWritten(meta(1));
        leveled.onTableWritten(meta(2));
        leveled.promote(0, meta(9));
        assertThat(leveled.tablesAt(0)).isEmpty();
        assertThat(leveled.tablesAt(1)).hasSize(1);
        assertThat(leveled.maxLevel()).isEqualTo(1);
        assertThat(leveled.tableCount()).isEqualTo(1);
    }

    @Test
    void invalidParamsRejected() {
        assertThatThrownBy(() ->
                new LeveledCompaction(0, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new LeveledCompaction(4, 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() ->
                new LeveledCompaction().onTableWritten(null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
