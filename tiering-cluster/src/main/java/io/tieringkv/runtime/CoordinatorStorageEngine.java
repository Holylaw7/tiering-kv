package io.tieringkv.runtime;

import io.tieringkv.mvcc.Transaction;
import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.Mutation;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.NoSuchElementException;
import java.util.Objects;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/**
 * 分布式事务网关存储适配器（v6.1.0 A1）：将标准 {@link StorageEngine} 操作桥接到
 * {@link RuntimeCoordinator} 的 2PC 事务路由器、跨 Region 扫描/计数与分布式快照。
 */
public final class CoordinatorStorageEngine implements StorageEngine {

    private static final long SCAN_TIMEOUT_SECONDS = 5;
    private static final long CHECKPOINT_TIMEOUT_SECONDS = 10;

    private final RuntimeCoordinator coordinator;

    public CoordinatorStorageEngine(RuntimeCoordinator coordinator) {
        this.coordinator = Objects.requireNonNull(coordinator, "coordinator");
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        Transaction txn = coordinator.router().begin();
        txn.put(key, value);
        coordinator.router().commit(txn);
    }

    @Override
    public byte[] get(byte[] key) {
        TxnMessages.Response response = coordinator.regionFor(key).get(key).join();
        if (response.succeeded() && response.message() != null && !response.message().isEmpty()) {
            return response.message().getBytes(StandardCharsets.UTF_8);
        }
        return null;
    }

    @Override
    public boolean delete(byte[] key) {
        Transaction txn = coordinator.router().begin();
        txn.delete(key);
        coordinator.router().commit(txn);
        return true;
    }

    @Override
    public boolean exists(byte[] key) {
        return get(key) != null;
    }

    @Override
    public int applyBatch(BatchWriteRequest request) {
        if (request.mutations().isEmpty()) {
            return 0;
        }
        Transaction txn = coordinator.router().begin();
        for (Mutation mutation : request.mutations()) {
            if (mutation.type() == Mutation.Type.PUT) {
                txn.put(mutation.key(), mutation.value());
            } else {
                txn.delete(mutation.key());
            }
        }
        coordinator.router().commit(txn);
        return request.mutations().size();
    }

    @Override
    public StorageIterator iterator() {
        List<RegionTxnClient> regions = coordinator.regions();
        List<CompletableFuture<List<TxnMessages.ScanEntry>>> futures = new ArrayList<>(regions.size());
        for (RegionTxnClient region : regions) {
            futures.add(region.scan().orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        TreeMap<byte[], byte[]> merged = new TreeMap<>(Arrays::compareUnsigned);
        for (CompletableFuture<List<TxnMessages.ScanEntry>> future : futures) {
            List<TxnMessages.ScanEntry> entries = future.join();
            for (TxnMessages.ScanEntry entry : entries) {
                merged.put(entry.key(), entry.value());
            }
        }
        long now = System.currentTimeMillis();
        Iterator<Map.Entry<byte[], byte[]>> it = merged.entrySet().iterator();
        return new StorageIterator() {
            @Override
            public boolean hasNext() {
                return it.hasNext();
            }

            @Override
            public KeyValueEntry next() {
                if (!it.hasNext()) {
                    throw new NoSuchElementException();
                }
                Map.Entry<byte[], byte[]> next = it.next();
                return KeyValueEntry.live(next.getKey(), next.getValue(), now, -1L, 1L);
            }

            @Override
            public void close() {
            }
        };
    }

    @Override
    public long size() {
        List<RegionTxnClient> regions = coordinator.regions();
        List<CompletableFuture<List<TxnMessages.ScanEntry>>> futures = new ArrayList<>(regions.size());
        for (RegionTxnClient region : regions) {
            futures.add(region.scan()
                    .orTimeout(SCAN_TIMEOUT_SECONDS, TimeUnit.SECONDS)
                    .exceptionally(ex -> List.of()));
        }
        long total = 0L;
        for (CompletableFuture<List<TxnMessages.ScanEntry>> future : futures) {
            total += future.join().size();
        }
        return total;
    }

    /** 跨全部 Region 扇出执行物理快照落盘；任一 Region 失败均抛出 IOException。 */
    public void checkpointAll() throws IOException {
        List<RegionTxnClient> regions = coordinator.regions();
        List<CompletableFuture<TxnMessages.Response>> futures = new ArrayList<>(regions.size());
        for (RegionTxnClient region : regions) {
            futures.add(region.checkpoint().orTimeout(CHECKPOINT_TIMEOUT_SECONDS, TimeUnit.SECONDS));
        }
        for (int i = 0; i < regions.size(); i++) {
            String regionId = regions.get(i).regionId();
            try {
                TxnMessages.Response response = futures.get(i).join();
                if (response == null || !response.succeeded()) {
                    String msg = response == null ? "null response" : response.message();
                    throw new IOException("region " + regionId + " checkpoint failed: " + msg);
                }
            } catch (IOException ex) {
                throw ex;
            } catch (RuntimeException ex) {
                throw new IOException("region " + regionId + " checkpoint error: " + ex.getMessage(), ex);
            }
        }
    }
}
