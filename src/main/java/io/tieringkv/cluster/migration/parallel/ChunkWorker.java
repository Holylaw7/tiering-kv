package io.tieringkv.cluster.migration.parallel;

import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.RawMutation;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;
import java.util.zip.CRC32C;

/** 分片迁移 worker（ADR-0063）：独立快照 + 零拷贝批写 + chunk 检查点。 */
final class ChunkWorker implements Runnable {

    private static final int BATCH_SIZE = 4096;
    private static final int MAX_RETRIES = 3;

    private final StorageEngine source;
    private final StorageEngine target;
    private final Path cursorDir;
    private final long versionBarrier;
    private final MigrationChunk chunk;
    private final PauseController pauseController;
    private final AtomicLong entries;
    private final AtomicLong bytes;
    private volatile ChunkCheckpoint.Status result;

    ChunkWorker(StorageEngine source, StorageEngine target, Path cursorDir,
                long versionBarrier, MigrationChunk chunk,
                PauseController pauseController,
                AtomicLong entries, AtomicLong bytes) {
        this.source = source;
        this.target = target;
        this.cursorDir = cursorDir;
        this.versionBarrier = versionBarrier;
        this.chunk = chunk;
        this.pauseController = pauseController;
        this.entries = entries;
        this.bytes = bytes;
    }

    @Override
    public void run() {
        for (int attempt = 0; attempt < MAX_RETRIES; attempt++) {
            try {
                runOnce();
                result = ChunkCheckpoint.Status.DONE;
                return;
            } catch (Exception e) {
                if (attempt == MAX_RETRIES - 1) {
                    ChunkCheckpoint failed = ChunkCheckpoint.empty(chunk.chunkId());
                    failed.markFailed();
                    try {
                        failed.persist(cursorDir);
                    } catch (IOException ignored) {
                        // best-effort
                    }
                    result = ChunkCheckpoint.Status.FAILED;
                }
            }
        }
    }

    private void runOnce() throws IOException {
        ChunkCheckpoint checkpoint = ChunkCheckpoint.load(cursorDir, chunk.chunkId());
        if (checkpoint.status() == ChunkCheckpoint.Status.DONE) {
            return;
        }
        checkpoint.markRunning();
        CRC32C crc = new CRC32C();
        crc.update(longToBytes(checkpoint.checksum()));
        List<RawMutation> batch = new ArrayList<>(BATCH_SIZE);
        try (StorageIterator iterator = iteratorFor(chunk)) {
            while (iterator.hasNext()) {
                KeyValueEntry entry = iterator.next();
                if (entry.version() > versionBarrier
                        || compare(entry.key(), checkpoint.lastKey()) <= 0) {
                    continue;
                }
                if (!chunk.covers(entry.key())) {
                    continue;
                }
                long ttl = entry.expireTimestamp() >= 0
                        ? Math.max(0, entry.expireTimestamp() - System.currentTimeMillis())
                        : -1;
                batch.add(new RawMutation(entry.key(), entry.value(),
                        entry.version(), ttl));
                crc.update(entry.key());
                crc.update(entry.value() == null ? new byte[0] : entry.value());
                checkpoint.advance(entry.key(), crc.getValue());
                entries.incrementAndGet();
                bytes.addAndGet(entry.key().length
                        + (entry.value() == null ? 0 : entry.value().length));
                pauseController.awaitNotPaused();
                if (batch.size() >= BATCH_SIZE) {
                    flush(checkpoint, batch);
                }
            }
        }
        flush(checkpoint, batch);
        checkpoint.markDone();
        checkpoint.persist(cursorDir);
    }

    private void flush(ChunkCheckpoint checkpoint, List<RawMutation> batch)
            throws IOException {
        if (batch.isEmpty()) {
            return;
        }
        target.applyRawBatch(batch);
        batch.clear();
        checkpoint.persist(cursorDir);
    }

    private StorageIterator iteratorFor(MigrationChunk chunk) {
        if (source instanceof MemTable memTable
                && chunk.segmentFrom() >= 0 && chunk.segmentTo() >= 0) {
            return memTable.segmentIterator(chunk.segmentFrom(), chunk.segmentTo());
        }
        return source.iterator();
    }

    ChunkCheckpoint.Status result() {
        return result;
    }

    private static int compare(byte[] a, byte[] b) {
        return Arrays.compareUnsigned(a, b);
    }

    private static byte[] longToBytes(long value) {
        return ByteBuffer.wrap(new byte[8])
                .order(ByteOrder.BIG_ENDIAN).putLong(value).array();
    }

    static final class PauseController {
        private volatile boolean paused;
        private final Object lock = new Object();

        void pause() {
            paused = true;
        }

        void resume() {
            paused = false;
            synchronized (lock) {
                lock.notifyAll();
            }
        }

        void awaitNotPaused() {
            if (!paused) {
                return;
            }
            synchronized (lock) {
                while (paused) {
                    try {
                        lock.wait();
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        return;
                    }
                }
            }
        }
    }
}
