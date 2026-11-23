package io.tieringkv.runtime;

import io.tieringkv.mvcc.Transaction;
import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.BatchWriteRequest;
import io.tieringkv.storage.memory.KeyValueEntry;
import io.tieringkv.storage.memory.Mutation;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.nio.charset.StandardCharsets;
import java.util.NoSuchElementException;
import java.util.Objects;

/**
 * 分布式事务网关存储适配器：将标准 {@link StorageEngine} 操作桥接到
 * {@link RuntimeCoordinator} 的 2PC 事务路由器与多 Region 读路径。
 */
public final class CoordinatorStorageEngine implements StorageEngine {

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
        return new StorageIterator() {
            @Override
            public boolean hasNext() {
                return false;
            }

            @Override
            public KeyValueEntry next() {
                throw new NoSuchElementException();
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
