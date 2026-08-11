package io.tieringkv.transaction;

import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.PersistentTxnJournal;
import io.tieringkv.mvcc.TxnJournal;
import io.tieringkv.mvcc.TxnRecoveryReplay;
import io.tieringkv.mvcc.TxnStateRecord;
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
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 磁盘故障混沌（ADR-0090 延续 / TD-044）：full/slow/readonly/corrupt。 */
class Phase22DiskChaosTest {

    @TempDir
    Path dir;

    @Test
    void diskFullOnCommitRollsBackNoDataLoss() throws Exception {
        MvccStorageEngine failing = new MvccStorageEngine(
                new FailOnSecondPut());
        TransactionParticipant participant = new TransactionParticipant(
                "r1", failing, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport), key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> c1, List.of(c1), metadata,
                new TransactionMetricsRegistry());
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        assertThatThrownBy(() -> router.commit(txn))
                .isInstanceOf(RuntimeException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.PREPARED);
        // 磁盘恢复后由恢复补完（无 committed data loss）
        assertThat(router.recover().committed()).isEqualTo(1);
        metadata.close();
    }

    @Test
    void diskSlowDuringCommitStillConsistent() throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(
                new SlowStorage(5));
        TransactionParticipant participant = new TransactionParticipant(
                "r1", engine, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport), key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> c1, List.of(c1), metadata,
                new TransactionMetricsRegistry());
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        router.commit(txn);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        metadata.close();
    }

    @Test
    void readonlyMetadataLogProposeFailsNoState() throws Exception {
        Path log = dir.resolve("readonly.log");
        Files.write(log, new byte[0]);
        TransactionMetadataService service =
                new TransactionMetadataService(
                        command -> {
                            throw new IllegalStateException("readonly fs");
                        }, log);
        assertThatThrownBy(() -> service.register("t1", bytes("a"), 1,
                java.util.Map.of("r1", List.of()))).isInstanceOf(
                RuntimeException.class);
        assertThat(service.state().size()).isZero();
        service.close();
    }

    @Test
    void walCorruptionTailTolerated() throws Exception {
        Path journalPath = dir.resolve("wal.log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory());
        journal.recordState(new TxnStateRecord("t1",
                TxnStateRecord.State.COMMIT, 1, 2, bytes("k"), List.of(
                new TxnStateRecord.Mutation(bytes("k"), bytes("v"), false))))
                .join();
        journal.close();
        Files.write(journalPath, new byte[]{0, 0, 0, 9, 1},
                java.nio.file.StandardOpenOption.APPEND);
        try (PersistentTxnJournal reopened = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory())) {
            assertThat(reopened.replay()).hasSize(1);
        }
    }

    @Test
    void walCorruptionMiddleThrows() throws Exception {
        Path journalPath = dir.resolve("wal-mid.log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory());
        journal.recordState(new TxnStateRecord("t1",
                TxnStateRecord.State.PREWRITE, 1, 0, bytes("k"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("k"), bytes("v"), false)))).join();
        journal.recordState(new TxnStateRecord("t1",
                TxnStateRecord.State.COMMIT, 1, 2, bytes("k"), List.of(
                new TxnStateRecord.Mutation(bytes("k"), bytes("v"), false))))
                .join();
        journal.close();
        byte[] data = Files.readAllBytes(journalPath);
        data[data.length / 2] ^= 0x7F;
        Files.write(journalPath, data);
        try (PersistentTxnJournal reopened = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory())) {
            assertThatThrownBy(reopened::replay)
                    .isInstanceOf(Exception.class);
        }
    }

    @Test
    void metadataLogTruncatedTailTolerated() throws Exception {
        Path log = dir.resolve("meta-trunc.log");
        TransactionMetadataService service =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L),
                        log);
        service.register("t1", bytes("a"), 1,
                java.util.Map.of("r1", List.of())).join();
        service.close();
        Files.write(log, new byte[]{0, 0, 0, 9, 1},
                java.nio.file.StandardOpenOption.APPEND);
        TransactionMetadataService recovered = TransactionMetadataService
                .recover(log, command -> CompletableFuture.completedFuture(1L));
        assertThat(recovered.state().size()).isEqualTo(1);
        recovered.close();
    }

    @ParameterizedTest(name = "failOnPut {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8})
    void parameterizedCommitFailureRecovers(int failOnPut) throws Exception {
        MvccStorageEngine failing = new MvccStorageEngine(
                new FailAtPut(failOnPut));
        TransactionParticipant participant = new TransactionParticipant(
                "r1", failing, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport), key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> c1, List.of(c1), metadata,
                new TransactionMetricsRegistry());
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        try {
            router.commit(txn);
        } catch (RuntimeException ignored) {
            // prewrite 或 commit 阶段故障
        }
        router.recover();
        if (failOnPut == 1) {
            // prewrite 失败 → 协调器已回滚，无数据
            assertThat(failing.latestValue(bytes("k"))).isNull();
        } else {
            // commit 阶段故障 → 决策已持久化，恢复补完
            assertThat(failing.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        }
        metadata.close();
    }

    @ParameterizedTest(name = "slow {0}")
    @ValueSource(longs = {1, 5, 10, 20})
    void parameterizedSlowDisk(long delayMillis) throws Exception {
        MvccStorageEngine engine = new MvccStorageEngine(
                new SlowStorage(delayMillis));
        TransactionParticipant participant = new TransactionParticipant(
                "r1", engine, new LockTable(), 60_000);
        LocalTxnTransport transport = new LocalTxnTransport(participant);
        RegionTxnClient c1 = new RegionTxnClient("r1",
                new TxnParticipantClient("n1", "r1", transport), key -> true);
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        DistributedTxnRouter router = new DistributedTxnRouter(
                new TimestampOracle(), key -> c1, List.of(c1), metadata,
                new TransactionMetricsRegistry());
        Transaction txn = router.begin();
        txn.put(bytes("k"), bytes("v"));
        router.commit(txn);
        assertThat(engine.latestValue(bytes("k"))).isEqualTo(bytes("v"));
        metadata.close();
    }

    @ParameterizedTest(name = "corrupt {0}")
    @ValueSource(ints = {2, 4, 6})
    void parameterizedCorruptionPosition(int byteIndex) throws Exception {
        Path journalPath = dir.resolve("wal-" + byteIndex + ".log");
        PersistentTxnJournal journal = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory());
        journal.recordState(new TxnStateRecord("t1",
                TxnStateRecord.State.PREWRITE, 1, 0, bytes("k"),
                List.of(new TxnStateRecord.Mutation(
                        bytes("k"), bytes("v"), false)))).join();
        journal.close();
        byte[] data = Files.readAllBytes(journalPath);
        if (byteIndex < data.length) {
            data[byteIndex] ^= 0x01;
        }
        Files.write(journalPath, data);
        try (PersistentTxnJournal reopened = new PersistentTxnJournal(
                journalPath, new TxnJournal.InMemory())) {
            // 单记录损坏必抛错；头部损坏同理
            assertThatThrownBy(reopened::replay)
                    .isInstanceOf(Exception.class);
        } catch (java.nio.file.NoSuchFileException e) {
            // 容忍文件缺失（不适用）
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    /** 第二次 put 失败（disk full 模拟）。 */
    private static final class FailOnSecondPut implements StorageEngine {
        private int puts;

        @Override
        public void put(byte[] key, byte[] value) {
            put(key, value, NO_TTL);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            if (++puts == 2) {
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

    /** 第 N 次 put 失败。 */
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
                throw new IllegalStateException("io error");
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

    /** 慢磁盘：put 前 sleep。 */
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
