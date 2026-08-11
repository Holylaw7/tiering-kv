package io.tieringkv.benchmark.mvcc;

import io.tieringkv.dr.DrDrillRunner;
import io.tieringkv.dr.DrRole;
import io.tieringkv.dr.DrSwitchPlanner;
import io.tieringkv.dr.DrTopology;
import io.tieringkv.replication.ReplicationMode;
import io.tieringkv.replication.BidirectionalPipeline;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.replication.crdt.GCounter;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantClusterPlanner;
import io.tieringkv.sql.AggregateType;
import io.tieringkv.sql.SqlEngine;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.hnsw.HnswIndex;
import io.tieringkv.cdc.ChangeEvent;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 28 基准（进程内口径，如实记录）：CRDT/复制/DR/SQL/HNSW。 */
class Phase28BenchmarkTest {

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1_000, 10_000})
    void crdtMergeThroughput(int ops) {
        GCounter a = new GCounter();
        GCounter b = new GCounter();
        long start = System.nanoTime();
        for (int i = 0; i < ops; i++) {
            a.increment("n1");
            b.increment("n2");
            a.merge(b);
            b.merge(a);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH CRDT %d -> %d ops/s%n",
                ops, ops * 1_000L / Math.max(1, elapsedMs));
        assertThat(a.value()).isEqualTo(ops * 2L);
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {100, 500})
    void bidirectionalWriteThroughput(int writes) {
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
        BidirectionalPipeline pipeline = new BidirectionalPipeline(
                List.of(sink), "r1", 5_000);
        long start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            pipeline.write(bytes("k" + i), bytes("v" + i)).join();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH BI-WRITE %d -> %d ops/s%n",
                writes, writes * 1_000L / Math.max(1, elapsedMs));
        assertThat(pipeline.get(bytes("k" + (writes - 1))))
                .isEqualTo(bytes("v" + (writes - 1)));
    }

    @Test
    void drFailoverRto() {
        DrTopology topology = new DrTopology(
                Map.of("a", DrRole.PRIMARY, "b", DrRole.SECONDARY),
                Map.of("a", ReplicationMode.SYNC));
        var plan = new DrSwitchPlanner().failover(topology, "a");
        long start = System.nanoTime();
        var result = new DrDrillRunner().run(plan, () -> true, 0);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH DR-RTO %d ms%n", elapsedMs);
        assertThat(result.success()).isTrue();
    }

    @ParameterizedTest(name = "rows {0}")
    @ValueSource(ints = {100, 1_000})
    void sqlJoinThroughput(int rows) {
        List<SqlEngine.Row> left = new ArrayList<>();
        List<SqlEngine.Row> right = new ArrayList<>();
        for (int i = 0; i < rows; i++) {
            left.add(new SqlEngine.Row(bytes("k" + i), bytes("L")));
            right.add(new SqlEngine.Row(bytes("k" + i), bytes("R")));
        }
        long start = System.nanoTime();
        assertThat(new SqlEngine().hashJoin(left, right,
                SqlEngine.Row::key, SqlEngine.Row::key)).hasSize(rows);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH SQL-JOIN %d -> %d ms%n",
                rows, elapsedMs);
    }

    @Test
    void sqlAggregateThroughput() {
        List<SqlEngine.Row> rows = new ArrayList<>();
        for (int i = 0; i < 10_000; i++) {
            rows.add(new SqlEngine.Row(bytes("k" + i),
                    bytes(String.valueOf(i))));
        }
        long start = System.nanoTime();
        long sum = new SqlEngine().aggregate(rows, AggregateType.SUM,
                row -> Long.parseLong(new String(row.value(),
                        StandardCharsets.UTF_8)));
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH SQL-AGG %d ms%n", elapsedMs);
        assertThat(sum).isGreaterThan(0);
    }

    @ParameterizedTest(name = "vectors {0}")
    @ValueSource(ints = {100, 1_000})
    void hnswSearchThroughput(int vectors) {
        HnswIndex index = new HnswIndex(4);
        List<Embedding> embeddings = new ArrayList<>();
        for (int i = 0; i < vectors; i++) {
            embeddings.add(new Embedding("e" + i,
                    new float[]{i % 7, 7 - i % 7}));
        }
        index.build(embeddings);
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(index.search(new float[]{1, 1}, 5)).hasSize(5);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH HNSW %d -> %d ms%n",
                vectors, elapsedMs);
    }

    @Test
    void hybridSearchThroughput() {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 1_000; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 5, 5 - i % 5}));
        }
        long start = System.nanoTime();
        for (int i = 0; i < 100; i++) {
            assertThat(store.search(new float[]{1, 1}, 10))
                    .hasSize(10);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH HYBRID %d ms%n", elapsedMs);
    }

    @Test
    void saasPlanLatency() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 5, 100);
        TenantClusterPlanner planner = new TenantClusterPlanner();
        long start = System.nanoTime();
        for (int i = 0; i < 1_000; i++) {
            planner.plan(tenant, 3, "v1");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE28-BENCH SAAS-PLAN %d ms%n", elapsedMs);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
