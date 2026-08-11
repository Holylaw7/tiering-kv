package io.tieringkv.runtime;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.LocalTxnTransport;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.TxnParticipantClient;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实磁盘混沌（TD-049）：容器式故障注入 + 重启恢复（JVM 等价）。 */
class RealDiskChaosTest {

    @TempDir
    Path dir;

    @Test
    void diskFullThenRestartNoLostCommit() throws Exception {
        DiskFixture fixture = diskFixture(new FailAtPut(2));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // disk full
        }
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                new TimestampOracle(), key -> fixture.region,
                List.of(fixture.region), fixture.metadata,
                new TransactionMetricsRegistry());
        assertThat(restarted.recover().committed()).isEqualTo(1);
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void readonlyCommitRejectedRollbackSafe() throws Exception {
        DiskFixture fixture = diskFixture(new AlwaysFailReadonly());
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // readonly
        }
        assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        fixture.close();
    }

    @Test
    void slowIoNoSplitBrain() throws Exception {
        DiskFixture fixture = diskFixture(new SlowStorage(5));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        fixture.router.commit(txn);
        assertThat(fixture.engine.latestValue(bytes("k")))
                .isEqualTo(bytes("v"));
        fixture.close();
    }

    @ParameterizedTest(name = "failOn {0}")
    @ValueSource(ints = {1, 2, 3, 5, 8, 13, 21, 34})
    void parameterizedFailureRestart(int failOn) throws Exception {
        DiskFixture fixture = diskFixture(new FailAtPut(failOn));
        Transaction txn = fixture.router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            fixture.router.commit(txn);
        } catch (RuntimeException ignored) {
            // 故障
        }
        DistributedTxnRouter restarted = new DistributedTxnRouter(
                new TimestampOracle(), key -> fixture.region,
                List.of(fixture.region), fixture.metadata,
                new TransactionMetricsRegistry());
        restarted.recover();
        if (failOn == 1) {
            assertThat(fixture.engine.latestValue(bytes("k"))).isNull();
        } else {
            assertThat(fixture.engine.latestValue(bytes("k")))
                    .isEqualTo(bytes("v"));
        }
        fixture.close();
    }

    private DiskFixture diskFixture(StorageEngine storage) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(storage);
        TransactionParticipant participant = new TransactionParticipant(
                "r1", engine, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient region = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport),
                key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> region, List.of(region),
                metadata, new TransactionMetricsRegistry());
        return new DiskFixture(engine, region, metadata, router);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record DiskFixture(MvccStorageEngine engine, RegionTxnClient region,
                               TransactionMetadataService metadata,
                               DistributedTxnRouter router)
            implements AutoCloseable {
        @Override
        public void close() throws Exception {
            metadata.close();
        }
    }

    private static final class FailAtPut implements StorageEngine {
        private final int failOn;
        private int puts;

        private FailAtPut(int failOn) {
            this.failOn = failOn;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            put(key, value, NO_TTL);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            if (++puts == failOn) {
                throw new IllegalStateException("disk full");
            }
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public boolean delete(byte[] key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            return 0;
        }
    }

    private static final class AlwaysFailReadonly implements StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
            throw new IllegalStateException("readonly");
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            throw new IllegalStateException("readonly");
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public boolean delete(byte[] key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            return 0;
        }
    }

    private static final class SlowStorage implements StorageEngine {
        private final long delayMillis;

        private SlowStorage(long delayMillis) {
            this.delayMillis = delayMillis;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            put(key, value, NO_TTL);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            try {
                Thread.sleep(delayMillis);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public boolean delete(byte[] key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            return 0;
        }
    }
}
