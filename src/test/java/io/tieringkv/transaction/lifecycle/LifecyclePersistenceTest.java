package io.tieringkv.transaction.lifecycle;

import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 生命周期持久化（ADR-0091）：MetadataRaft 存储 + 重启恢复。 */
class LifecyclePersistenceTest {

    @TempDir
    Path dir;

    @Test
    void beginPersistsActive() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        assertThat(fixture.metadata.lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.ACTIVE);
        fixture.close();
    }

    @Test
    void prewritePersisted() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markPrewrite("t1");
        assertThat(fixture.metadata.lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.PREWRITE);
        fixture.close();
    }

    @Test
    void committedPersisted() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markCommitted("t1");
        assertThat(fixture.metadata.lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.COMMITTED);
        fixture.close();
    }

    @Test
    void rollbackPersisted() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markRolledBack("t1");
        assertThat(fixture.metadata.lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.ROLLED_BACK);
        fixture.close();
    }

    @Test
    void expiredPersisted() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markExpired("t1");
        assertThat(fixture.metadata.lifecycleSnapshot().get("t1").state())
                .isEqualTo(TxnLifecycleState.EXPIRED);
        fixture.close();
    }

    @Test
    void heartbeatPersisted() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.heartbeat("t1", System.currentTimeMillis() + 100);
        TxnLifecycleRecord record = fixture.metadata.lifecycleSnapshot()
                .get("t1");
        assertThat(record.expireAtMillis()).isGreaterThan(0);
        fixture.close();
    }

    @Test
    void restartRecoversLifecycle() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markPrewrite("t1");
        fixture.close();
        TransactionLifecycleManager recovered =
                new TransactionLifecycleManager(metadataFrom(fixture.log));
        assertThat(recovered.recoverFromMetadata(1000, 5000)).isEqualTo(1);
        assertThat(recovered.get("t1").state())
                .isEqualTo(TxnLifecycleState.PREWRITE);
        recovered.markCommitted("t1");
    }

    @Test
    void restartAbortsExpired() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 0, 5000);
        Thread.sleep(30);
        fixture.close();
        TransactionLifecycleManager recovered =
                new TransactionLifecycleManager(metadataFrom(fixture.log));
        recovered.recoverFromMetadata(10, 5000);
        assertThat(recovered.get("t1").state())
                .isEqualTo(TxnLifecycleState.EXPIRED);
    }

    @Test
    void terminalStatesSkippedOnRecovery() throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, 1000, 5000);
        fixture.lifecycle.markCommitted("t1");
        fixture.close();
        TransactionLifecycleManager recovered =
                new TransactionLifecycleManager(metadataFrom(fixture.log));
        assertThat(recovered.recoverFromMetadata(1000, 5000)).isZero();
    }

    @ParameterizedTest(name = "ttl {0}")
    @ValueSource(longs = {5, 10, 20, 40, 80, 160, 320, 640, 100, 200,
            400, 800, 1200, 2000, 3000, 5000})
    void parameterizedTtlPersisted(long ttlMillis) throws Exception {
        ServiceFixture fixture = service();
        Transaction txn = new Transaction("t1", 1);
        fixture.lifecycle.begin(txn, ttlMillis, 5000);
        TxnLifecycleRecord record = fixture.metadata.lifecycleSnapshot()
                .get("t1");
        assertThat(record.expireAtMillis())
                .isGreaterThanOrEqualTo(System.currentTimeMillis());
        fixture.close();
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 2, 4, 8, 16, 32})
    void parameterizedManyLifecycles(int count) throws Exception {
        ServiceFixture fixture = service();
        for (int i = 0; i < count; i++) {
            fixture.lifecycle.begin(new Transaction("t" + i, i), 1000, 5000);
        }
        assertThat(fixture.metadata.lifecycleSnapshot()).hasSize(count);
        fixture.close();
    }

    private ServiceFixture service() throws Exception {
        Path log = dir.resolve("lifecycle-" + System.nanoTime() + ".log");
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        log);
        TransactionLifecycleManager lifecycle =
                new TransactionLifecycleManager(metadata);
        return new ServiceFixture(metadata, lifecycle, log);
    }

    private TransactionMetadataService metadataFrom(Path log)
            throws Exception {
        return TransactionMetadataService.recover(log,
                command -> CompletableFuture.completedFuture(1L));
    }

    private record ServiceFixture(TransactionMetadataService metadata,
                                  TransactionLifecycleManager lifecycle,
                                  Path log) implements AutoCloseable {
        @Override
        public void close() throws Exception {
            metadata.close();
        }
    }
}
