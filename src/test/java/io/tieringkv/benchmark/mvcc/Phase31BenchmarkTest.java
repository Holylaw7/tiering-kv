package io.tieringkv.benchmark.mvcc;

import io.tieringkv.replication.active.ActiveActivePipeline;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.sharding.auto.AutoReshardController;
import io.tieringkv.sharding.auto.LoadProbe;
import io.tieringkv.sharding.auto.ReshardPolicy;
import io.tieringkv.sql.txn.SqlTxn2PcBridge;
import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.cluster.VectorDoubleWriteRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 31 基准（进程内口径，如实记录）。 */
class Phase31BenchmarkTest {

    @ParameterizedTest(name = "decisions {0}")
    @ValueSource(ints = {1_000, 10_000})
    void autoReshardDecisions(int count) {
        AutoReshardController controller =
                new AutoReshardController(new ReshardPolicy(
                        1000, 100, 0, 3));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            controller.decide(new LoadProbe(2000, 5, 100));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE31-BENCH AUTORESHARD %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {100, 1000})
    void activeActiveWriteThroughput(int writes) {
        ReplicaSink sink = new ReplicaSink() {
            @Override
            public CompletableFuture<Void> apply(ChangeEvent event) {
                return CompletableFuture.completedFuture(null);
            }

            @Override
            public String replicaId() {
                return "r2";
            }
        };
        ActiveActivePipeline pipeline = new ActiveActivePipeline(
                List.of(sink), "r1", 5_000);
        long start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE31-BENCH ACTIVE-WRITE %d -> %d ops/s%n",
                writes, writes * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {100, 1000})
    void sql2pcBridgeThroughput(int writes) {
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> true);
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", writes2 -> bridge.commit(writes2));
        long start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            executor.execute(new SqlTxnParser().parse("BEGIN"));
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
            executor.execute(new SqlTxnParser().parse("COMMIT"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE31-BENCH SQL2PC %d -> %d txn/s%n",
                writes, writes * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {100, 1000})
    void doubleWriteSearchThroughput(int count) {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        for (int i = 0; i < count; i++) {
            router.put(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(router.search(new float[]{1, 1}, 5))
                    .hasSize(5);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE31-BENCH DW-SEARCH %d -> %d ms%n",
                count, elapsedMs);
    }

    @Test
    void conflictMetricsLatency() {
        io.tieringkv.replication.active.ConflictMetrics metrics =
                new io.tieringkv.replication.active.ConflictMetrics();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            metrics.recordConflict();
            metrics.recordConvergence(i % 100);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE31-BENCH CONFLICT-METRICS %d ms%n",
                elapsedMs);
        assertThat(metrics.conflicts()).isEqualTo(10_000);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
