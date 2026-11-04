package io.tieringkv.storage.tiering;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.Arrays;
import java.util.NoSuchElementException;

/**
 * 热层 + 冷层的归并迭代器（TD-086）。
 *
 * <p>两层都按 key 的无符号字典序有序；同键时**热层优先**（热层更新），
 * 冷层的旧版本被丢弃。冷层迭代器已过滤墓碑与过期条目。
 */
final class UnionStorageIterator implements StorageIterator {

    private final StorageIterator hot;
    private final StorageIterator cold;
    private KeyValueEntry nextHot;
    private KeyValueEntry nextCold;

    UnionStorageIterator(StorageIterator hot, StorageIterator cold) {
        this.hot = hot;
        this.cold = cold;
        advanceHot();
        advanceCold();
    }

    private void advanceHot() {
        nextHot = hot.hasNext() ? hot.next() : null;
    }

    private void advanceCold() {
        nextCold = cold.hasNext() ? cold.next() : null;
    }

    @Override
    public boolean hasNext() {
        return nextHot != null || nextCold != null;
    }

    @Override
    public KeyValueEntry next() {
        if (!hasNext()) {
            throw new NoSuchElementException("iterator exhausted");
        }
        if (nextHot == null) {
            KeyValueEntry result = nextCold;
            advanceCold();
            return result;
        }
        if (nextCold == null) {
            KeyValueEntry result = nextHot;
            advanceHot();
            return result;
        }
        int cmp = Arrays.compareUnsigned(nextHot.key(), nextCold.key());
        if (cmp < 0) {
            KeyValueEntry result = nextHot;
            advanceHot();
            return result;
        }
        if (cmp > 0) {
            KeyValueEntry result = nextCold;
            advanceCold();
            return result;
        }
        // 同键：热层优先，冷层旧值跳过
        KeyValueEntry result = nextHot;
        advanceHot();
        advanceCold();
        return result;
    }

    @Override
    public void close() {
        hot.close();
        cold.close();
    }
}
