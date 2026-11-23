package io.tieringkv.storage;

import io.tieringkv.storage.compaction.LeveledCompactionExecutor;
import io.tieringkv.storage.compaction.LeveledCompactionExecutor.Entry;
import io.tieringkv.storage.compaction.LeveledCompactionExecutor.ExecutionResult;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner;
import io.tieringkv.storage.compaction.LeveledCompactionPlanner.CompactionPlan;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Leveled 执行（ADR-0207）：合并 + tombstone + TTL。 */
class LeveledExecutorTest {

    private final LeveledCompactionExecutor executor =
            new LeveledCompactionExecutor();
    private final LeveledCompactionPlanner planner =
            new LeveledCompactionPlanner();

    @Test
    void latestWinsOnDuplicateKeys() {
        CompactionPlan plan = planner.planLevel(200, 100, 64, 0);
        List<Entry> entries = List.of(
                entry("k1", "old", false, 0),
                entry("k1", "new", false, 0),
                entry("k2", "v2", false, 0));
        ExecutionResult result = executor.execute(plan, entries, 0);
        assertThat(result.deletedEntries()).isZero();
        assertThat(executor.summarize(entries, 0).keySet())
                .containsExactly("k1", "k2");
    }

    @Test
    void tombstoneRemovesKey() {
        CompactionPlan plan = planner.planLevel(200, 100, 64, 0);
        List<Entry> entries = List.of(
                entry("k1", "v1", false, 0),
                entry("k1", null, true, 0));
        ExecutionResult result = executor.execute(plan, entries, 0);
        assertThat(result.deletedEntries()).isEqualTo(1);
        assertThat(executor.summarize(entries, 0)).isEmpty();
    }

    @Test
    void expiredEntryCleaned() {
        CompactionPlan plan = planner.planLevel(200, 100, 64, 0);
        List<Entry> entries = List.of(
                entry("k1", "v1", false, 10),
                entry("k2", "v2", false, 0));
        ExecutionResult result = executor.execute(plan, entries, 100);
        assertThat(result.deletedEntries()).isEqualTo(1);
        assertThat(executor.summarize(entries, 100).keySet())
                .containsExactly("k2");
    }

    @Test
    void noCompactPlanZeroOutput() {
        CompactionPlan plan = planner.planLevel(50, 100, 64, 1);
        ExecutionResult result = executor.execute(plan,
                List.of(entry("k1", "v1", false, 0)), 0);
        assertThat(result.outputFiles()).isZero();
    }

    @Test
    void nullInputsRejected() {
        assertThatThrownBy(() -> executor.execute(null, List.of(), 0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> executor.execute(
                planner.planLevel(200, 100, 64, 0), null, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void fileSequenceAdvances() {
        CompactionPlan plan = planner.planLevel(500, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            entries.add(entry("k" + i, "v", false, 0));
        }
        long before = executor.fileSequence();
        executor.execute(plan, entries, 0);
        assertThat(executor.fileSequence()).isGreaterThan(before);
    }

    @ParameterizedTest(name = "entries {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedEntryCounts(int count) {
        CompactionPlan plan = planner.planLevel(500, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            entries.add(entry("k" + i, "v", false, 0));
        }
        assertThat(executor.summarize(entries, 0)).hasSize(count);
    }

    @ParameterizedTest(name = "expired {0}")
    @ValueSource(ints = {0, 5, 20})
    void parameterizedExpiredCounts(int expired) {
        CompactionPlan plan = planner.planLevel(500, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 20; i++) {
            entries.add(entry("k" + i, "v", false,
                    i < expired ? 10 : 0));
        }
        ExecutionResult result = executor.execute(plan, entries, 100);
        assertThat(result.deletedEntries()).isEqualTo(expired);
    }

    @Test
    void concurrentExecuteStable() throws Exception {
        LeveledCompactionExecutor local =
                new LeveledCompactionExecutor();
        CompactionPlan plan = planner.planLevel(500, 100, 64, 0);
        List<Entry> entries = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            entries.add(entry("k" + i, "v", false, 0));
        }
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 20; i++) {
                    local.execute(plan, entries, 0);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(local.fileSequence()).isGreaterThan(0);
    }

    private static Entry entry(String key, String value,
                               boolean deleted, long expireAt) {
        return new Entry(key,
                value == null ? null : value.getBytes(),
                deleted, expireAt);
    }
}
