package io.tieringkv.cluster.migration.streaming;

import io.tieringkv.storage.memory.Mutation;

import java.util.ArrayList;
import java.util.List;

/**
 * 动态批量编码（ADR-0053）：按 entry 大小选择 batch 规模——
 * 100B→4096、1KB→1024、10KB→256，减少网络/apply 调用次数。
 */
public final class BatchEncoder {

    private final List<Mutation> pending = new ArrayList<>();
    private final int batchSize;

    public BatchEncoder(int batchSize) {
        if (batchSize <= 0) {
            throw new IllegalArgumentException("batch size must be positive");
        }
        this.batchSize = batchSize;
    }

    public static int batchSizeFor(int entryBytes) {
        if (entryBytes <= 256) {
            return 4096;
        }
        if (entryBytes <= 2048) {
            return 1024;
        }
        return 256;
    }

    public boolean add(Mutation mutation) {
        pending.add(mutation);
        return pending.size() >= batchSize;
    }

    public boolean isFull() {
        return pending.size() >= batchSize;
    }

    public List<Mutation> drain() {
        List<Mutation> batch = List.copyOf(pending);
        pending.clear();
        return batch;
    }

    public boolean isEmpty() {
        return pending.isEmpty();
    }

    public int batchSize() {
        return batchSize;
    }
}
