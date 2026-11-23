package io.tieringkv.storage.memory;

import java.util.List;

/** 批量写请求（ADR-0048）：有序变更列表，原子应用。 */
public record BatchWriteRequest(List<Mutation> mutations) {

    public static final int MAX_BATCH_SIZE = 4096;

    public BatchWriteRequest {
        mutations = List.copyOf(mutations);
        if (mutations.isEmpty()) {
            throw new IllegalArgumentException("batch must not be empty");
        }
        if (mutations.size() > MAX_BATCH_SIZE) {
            throw new IllegalArgumentException("batch too large: " + mutations.size());
        }
    }

    public static BatchWriteRequest of(Mutation... mutations) {
        return new BatchWriteRequest(List.of(mutations));
    }
}
