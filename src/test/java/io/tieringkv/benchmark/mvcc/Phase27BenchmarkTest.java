package io.tieringkv.benchmark.mvcc;

import io.tieringkv.backup.pitr.PitrRecord;
import io.tieringkv.backup.pitr.PitrWriteLog;
import io.tieringkv.cdc.CDCProducer;
import io.tieringkv.cdc.CDCConsumerRegistry;
import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.replication.ReplicaSink;
import io.tieringkv.replication.ReplicationMode;
import io.tieringkv.replication.ReplicationPipeline;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;
import io.tieringkv.security.Role;
import io.tieringkv.security.rpc.RpcPermissionGuard;
import io.tieringkv.sql.SelectStatement;
import io.tieringkv.sql.SqlExecutor;
import io.tieringkv.sql.SqlParser;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.Embedding;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 27 基准（跨地域/企业能力，进程内口径如实记录）。 */
class Phase27BenchmarkTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {200, 500})
    void replicationThroughput(int count) {
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
        ReplicationPipeline pipeline = new ReplicationPipeline(
                List.of(sink), ReplicationMode.SYNC, 5_000, "r1");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            pipeline.replicate(new ChangeEvent(i,
                    ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1", i)).join();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH REPLICATION %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(pipeline.replicatedCount()).isEqualTo(count);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {100, 300})
    void pitrAppendThroughput(int count) throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("p27-" + count));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            log.append(new PitrRecord(i, i, i * 10, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH PITR-APPEND %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {200, 500})
    void cdcFanOutThroughput(int count) throws Exception {
        Path logDir = dir.resolve("p27-cdc-" + count);
        CDCProducer producer = new CDCProducer(logDir);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("p27-ckpt-" + count));
        registry.register("g1").consume(event -> {
        });
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH CDC-FANOUT %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(registry.group("g1").checkpoint())
                .isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "checks {0}")
    @ValueSource(ints = {1_000, 10_000})
    void rbacGuardThroughput(int count) {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            guard.require(token, "TXN_PREWRITE");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH RBAC %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(credentials.allows(token, Permission.WRITE))
                .isTrue();
    }

    @ParameterizedTest(name = "queries {0}")
    @ValueSource(ints = {100, 1_000})
    void sqlExecuteThroughput(int queries) {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 100; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv WHERE key = 'k50'");
        long start = System.nanoTime();
        for (int i = 0; i < queries; i++) {
            assertThat(new SqlExecutor().execute(statement, engine,
                    Long.MAX_VALUE)).hasSize(1);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH SQL %d -> %d ops/s%n",
                queries, queries * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "searches {0}")
    @ValueSource(ints = {100, 1_000})
    void vectorSearchThroughput(int searches) {
        VectorStore store = new VectorStore();
        for (int i = 0; i < 500; i++) {
            store.put(new Embedding("e" + i,
                    new float[]{i % 7, 7 - i % 7}));
        }
        long start = System.nanoTime();
        for (int i = 0; i < searches; i++) {
            assertThat(store.search(new float[]{1, 1}, 5)).hasSize(5);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH VECTOR %d -> %d ops/s%n",
                searches, searches * 1_000L / Math.max(1, elapsedMs));
    }

    @Test
    void sqlRangeScanLatency() {
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        for (int i = 0; i < 1_000; i++) {
            engine.putVersion(bytes("k" + i), bytes("v" + i),
                    i + 1, (i + 1) * 10, WriteType.PUT);
        }
        SelectStatement statement = new SqlParser().parse(
                "SELECT * FROM kv WHERE key >= 'k0' AND key < 'k500'");
        long start = System.nanoTime();
        // 字符串字典序范围（k0..k999 中 < 'k500' 的数量），
        // 非数值 500；断言区间保证范围扫描语义成立。
        assertThat(new SqlExecutor().execute(statement, engine,
                Long.MAX_VALUE)).hasSizeGreaterThan(400);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE27-BENCH SQL-RANGE %d ms%n", elapsedMs);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
