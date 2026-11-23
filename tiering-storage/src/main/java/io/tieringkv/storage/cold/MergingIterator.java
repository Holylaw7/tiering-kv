package io.tieringkv.storage.cold;

import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.ArrayList;
import java.util.List;
import java.util.PriorityQueue;

/**
 * 多源 k-way 合并迭代（ADR-0019）：键升序；同键时**新源优先**（priority 大者
 * 胜出），旧源同键条目被跳过。
 */
public final class MergingIterator implements StorageIterator {

    private final PriorityQueue<Cursor> heap;

    public MergingIterator(List<Source> sources) {
        this.heap = new PriorityQueue<>(sources.size(), (a, b) -> {
            int cmp = Keys.compare(a.peek().key(), b.peek().key());
            return cmp != 0 ? cmp : Integer.compare(b.source.priority(), a.source.priority());
        });
        for (Source source : sources) {
            Cursor cursor = new Cursor(source);
            if (cursor.hasNext()) {
                heap.offer(cursor);
            }
        }
    }

    @Override
    public boolean hasNext() {
        return !heap.isEmpty();
    }

    @Override
    public KeyValueEntry next() {
        Cursor top = heap.poll();
        KeyValueEntry winner = top.nextEntry();
        // 跳过所有同键的旧源条目（新源优先）
        while (!heap.isEmpty() && Keys.compare(heap.peek().peek().key(), winner.key()) == 0) {
            Cursor duplicate = heap.poll();
            duplicate.advance();
            if (duplicate.hasNext()) {
                heap.offer(duplicate);
            }
        }
        if (top.hasNext()) {
            heap.offer(top);
        }
        return winner;
    }

    @Override
    public void close() {
    }

    /** 数据源：迭代器 + 优先级（新表/新写入更大）。 */
    public record Source(StorageIterator iterator, int priority) {
    }

    private static final class Cursor {
        private final Source source;
        private final StorageIterator iterator;
        private KeyValueEntry current;

        private Cursor(Source source) {
            this.source = source;
            this.iterator = source.iterator();
        }

        private KeyValueEntry peek() {
            return current;
        }

        private KeyValueEntry nextEntry() {
            KeyValueEntry entry = current;
            current = null;
            return entry;
        }

        private boolean hasNext() {
            if (current == null && iterator.hasNext()) {
                current = iterator.next();
            }
            return current != null;
        }

        private void advance() {
            current = null;
        }
    }

    /** 便捷：把有序 List 包装为 Source（priority 用于新源优先）。 */
    public static List<Source> sources(List<List<KeyValueEntry>> lists, int basePriority) {
        List<Source> sources = new ArrayList<>(lists.size());
        for (int i = 0; i < lists.size(); i++) {
            sources.add(new Source(new ListIterator(lists.get(i)), basePriority + i));
        }
        return sources;
    }

    static final class ListIterator implements StorageIterator {
        private final List<KeyValueEntry> entries;
        private int index;

        ListIterator(List<KeyValueEntry> entries) {
            this.entries = entries;
        }

        @Override
        public boolean hasNext() {
            return index < entries.size();
        }

        @Override
        public KeyValueEntry next() {
            return entries.get(index++);
        }

        @Override
        public void close() {
        }
    }
}
