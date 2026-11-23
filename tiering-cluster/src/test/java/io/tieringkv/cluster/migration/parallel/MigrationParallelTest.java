package io.tieringkv.cluster.migration.parallel;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.RawMutation;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 并行迁移（ADR-0063）：多 worker / checkpoint / CRC / retry / pause-resume。 */
class MigrationParallelTest {

    @TempDir
    Path dir;

    @Test
    void migratesAllEntries2Chunks() throws Exception {
        MemTable source = source(10_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(2);
            assertThat(summary.failedChunks()).isZero();
            assertThat(target.size()).isEqualTo(10_000);
            assertThat(summary.entries()).isEqualTo(10_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migratesAllEntries4Chunks() throws Exception {
        MemTable source = source(20_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 4, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(4);
            assertThat(summary.doneChunks()).isEqualTo(4);
            assertThat(target.size()).isEqualTo(20_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migratesAllEntries8Chunks() throws Exception {
        MemTable source = source(30_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 8, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(8);
            assertThat(summary.doneChunks()).isEqualTo(8);
            assertThat(target.size()).isEqualTo(30_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void checkpointCreatedPerChunk() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 4, Long.MAX_VALUE);
            manager.migrate(4);
            for (int i = 0; i < 4; i++) {
                assertThat(Files.exists(dir.resolve("chunk-" + i + ".ckpt")))
                        .isTrue();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void checkpointContainsChecksum() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            manager.migrate(2);
            ChunkCheckpoint checkpoint = ChunkCheckpoint.load(dir, 0);
            assertThat(checkpoint.status()).isEqualTo(ChunkCheckpoint.Status.DONE);
            assertThat(checkpoint.checksum()).isNotZero();
            assertThat(checkpoint.offset()).isGreaterThan(0);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void migrateIsIdempotent() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            manager.migrate(2);
            RegionTransferManager.MigrationSummary second = manager.migrate(2);
            assertThat(second.entries()).isZero();
            assertThat(target.size()).isEqualTo(5_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void missingCheckpointResumesChunk() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            manager.migrate(2);
            Files.delete(dir.resolve("chunk-1.ckpt"));
            RegionTransferManager.MigrationSummary resumed = manager.migrate(2);
            assertThat(resumed.entries()).isGreaterThan(0);
            assertThat(target.size()).isEqualTo(5_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void corruptCheckpointFallsBack() throws Exception {
        MemTable source = source(5_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 1, Long.MAX_VALUE);
            manager.migrate(1);
            byte[] bytes = Files.readAllBytes(dir.resolve("chunk-0.ckpt"));
            bytes[bytes.length - 1] ^= 0x01;
            Files.write(dir.resolve("chunk-0.ckpt"), bytes);
            RegionTransferManager.MigrationSummary resumed = manager.migrate(1);
            assertThat(resumed.entries()).isEqualTo(5_000); // 从头重迁
            assertThat(target.size()).isEqualTo(5_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void pauseResumeControlsWorkers() throws Exception {
        MemTable source = source(10_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            manager.pause();
            CountDownLatch done = new CountDownLatch(1);
            AtomicBoolean failed = new AtomicBoolean();
            Thread runner = new Thread(() -> {
                try {
                    manager.migrate(2);
                } catch (Exception e) {
                    failed.set(true);
                } finally {
                    done.countDown();
                }
            });
            runner.start();
            Thread.sleep(300);
            assertThat(target.size()).isZero(); // 暂停中无写入
            manager.resume();
            assertThat(done.await(30, TimeUnit.SECONDS)).isTrue();
            assertThat(failed.get()).isFalse();
            assertThat(target.size()).isEqualTo(10_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void retryOnTransientFailure() throws Exception {
        MemTable source = source(2_000);
        FaultyStorage target = new FaultyStorage(MemTable.create(), 1);
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 1, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(1);
            assertThat(summary.failedChunks()).isZero();
            assertThat(target.size()).isEqualTo(2_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void failedChunksReportedAfterRetriesExhausted() throws Exception {
        MemTable source = source(1_000);
        FaultyStorage target = new FaultyStorage(MemTable.create(), 100);
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 1, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(1);
            assertThat(summary.failedChunks()).isEqualTo(1);
            assertThat(ChunkCheckpoint.load(dir, 0).status())
                    .isEqualTo(ChunkCheckpoint.Status.FAILED);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void defaultWorkerCountCappedAtEight() {
        assertThat(RegionTransferManager.defaultWorkerCount())
                .isLessThanOrEqualTo(8)
                .isGreaterThan(0);
    }

    @Test
    void versionBarrierSkipsNewerEntries() throws Exception {
        MemTable source = MemTable.create();
        for (int i = 0; i < 1_000; i++) {
            source.put(key(i), value());
        }
        long barrier = source.getEntry(key(500)).version() - 1;
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, barrier);
            RegionTransferManager.MigrationSummary summary = manager.migrate(2);
            assertThat(target.size()).isLessThan(1_000);
            assertThat(target.size()).isGreaterThan(0);
            assertThat(summary.entries()).isEqualTo(target.size());
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void bytesReportedMatchesEntries() throws Exception {
        MemTable source = source(1_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(2);
            assertThat(summary.bytes()).isGreaterThan(0);
            assertThat(summary.mbPerSec()).isGreaterThan(0);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void emptySourceCompletes() throws Exception {
        MemTable source = MemTable.create();
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 4, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(4);
            assertThat(summary.failedChunks()).isZero();
            assertThat(summary.entries()).isZero();
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void invalidChunkCountRejected() throws Exception {
        MemTable source = source(10);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 2, Long.MAX_VALUE);
            assertThatThrownBy(() -> manager.migrate(0))
                    .isInstanceOf(IllegalArgumentException.class);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void workerCountRespected() throws Exception {
        MemTable source = source(1_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 3, Long.MAX_VALUE);
            assertThat(manager.workerCount()).isEqualTo(3);
            manager.migrate(3);
            assertThat(target.size()).isEqualTo(1_000);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void targetSizeEqualsSource() throws Exception {
        MemTable source = source(7_777);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 8, Long.MAX_VALUE);
            manager.migrate(8);
            assertThat(target.size()).isEqualTo(source.size());
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void segmentChunkingUsesAllSegments() throws Exception {
        MemTable source = source(10_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 8, Long.MAX_VALUE);
            manager.migrate(8);
            int files = (int) Files.list(dir)
                    .filter(p -> p.getFileName().toString().endsWith(".ckpt"))
                    .count();
            assertThat(files).isEqualTo(8);
        } finally {
            source.close();
            target.close();
        }
    }

    @Test
    void genericStorageFallsBackToSingleChunk() throws Exception {
        MemTable inner = source(1_000);
        GenericStorage generic = new GenericStorage(inner);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    generic, target, dir, 4, Long.MAX_VALUE);
            RegionTransferManager.MigrationSummary summary = manager.migrate(4);
            assertThat(summary.chunkCount()).isEqualTo(1);
            assertThat(target.size()).isEqualTo(1_000);
        } finally {
            generic.close();
            target.close();
        }
    }

    @Test
    void concurrentChunksDoNotOverwrite() throws Exception {
        MemTable source = source(20_000);
        MemTable target = MemTable.create();
        try {
            RegionTransferManager manager = new RegionTransferManager(
                    source, target, dir, 8, Long.MAX_VALUE);
            manager.migrate(8);
            for (int i = 0; i < 20_000; i++) {
                assertThat(target.get(key(i))).isNotNull();
            }
        } finally {
            source.close();
            target.close();
        }
    }

    private static MemTable source(int count) {
        MemTable table = MemTable.create();
        byte[] value = value();
        for (int i = 0; i < count; i++) {
            table.put(key(i), value);
        }
        return table;
    }

    private static byte[] key(int i) {
        return ("pm:" + String.format("%05d", i)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value() {
        return new byte[16];
    }

    /** 可注入 applyRawBatch 失败的存储包装。 */
    private static final class FaultyStorage implements StorageEngine {
        private final MemTable delegate;
        private final int failures;
        private int remaining;

        private FaultyStorage(MemTable delegate, int failures) {
            this.delegate = delegate;
            this.failures = failures;
            this.remaining = failures;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            delegate.put(key, value);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            delegate.put(key, value, ttlMillis);
        }

        @Override
        public byte[] get(byte[] key) {
            return delegate.get(key);
        }

        @Override
        public boolean delete(byte[] key) {
            return delegate.delete(key);
        }

        @Override
        public boolean exists(byte[] key) {
            return delegate.exists(key);
        }

        @Override
        public StorageIterator iterator() {
            return delegate.iterator();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        @Override
        public int applyRawBatch(java.util.List<RawMutation> mutations) {
            if (remaining > 0) {
                remaining--;
                throw new IllegalStateException("injected failure");
            }
            return delegate.applyRawBatch(mutations);
        }

        public void close() {
            delegate.close();
        }
    }

    /** 通用存储包装：触发 key-range fallback 路径。 */
    private static final class GenericStorage implements StorageEngine {
        private final MemTable delegate;

        private GenericStorage(MemTable delegate) {
            this.delegate = delegate;
        }

        @Override
        public void put(byte[] key, byte[] value) {
            delegate.put(key, value);
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            delegate.put(key, value, ttlMillis);
        }

        @Override
        public byte[] get(byte[] key) {
            return delegate.get(key);
        }

        @Override
        public boolean delete(byte[] key) {
            return delegate.delete(key);
        }

        @Override
        public boolean exists(byte[] key) {
            return delegate.exists(key);
        }

        @Override
        public StorageIterator iterator() {
            return delegate.iterator();
        }

        @Override
        public long size() {
            return delegate.size();
        }

        public void close() {
            delegate.close();
        }
    }
}
