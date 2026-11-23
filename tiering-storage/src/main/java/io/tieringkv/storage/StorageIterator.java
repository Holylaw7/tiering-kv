package io.tieringkv.storage;

import io.tieringkv.storage.memory.KeyValueEntry;

/** 有序迭代器（按 key 无符号字典序），返回快照中的存活 entry。 */
public interface StorageIterator extends AutoCloseable {

    boolean hasNext();

    KeyValueEntry next();

    @Override
    void close();
}
