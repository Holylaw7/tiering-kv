package io.tieringkv.cluster.lifecycle.split;

import io.tieringkv.storage.memory.RawMutation;

import java.util.ArrayList;
import java.util.List;

/** 分裂窗口写缓冲（ADR-0061）：SPLITTING 期间写入暂存，COMMIT 分发。 */
public final class SplitWriteBuffer {

    private final List<RawMutation> mutations = new ArrayList<>();

    public synchronized void add(byte[] key, byte[] value, long version, long ttlMillis) {
        mutations.add(new RawMutation(key, value, version, ttlMillis));
    }

    public synchronized int size() {
        return mutations.size();
    }

    public synchronized List<RawMutation> drain() {
        List<RawMutation> copy = List.copyOf(mutations);
        mutations.clear();
        return copy;
    }
}
