package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.util.ArrayList;
import java.util.List;

/** 内存日志（Phase 11 语义；单元测试与进程内原型使用）。 */
public final class MemoryRaftLog implements RaftLog {

    private final List<LogEntry> entries = new ArrayList<>();
    private long baseIndex;

    @Override
    public void append(LogEntry entry) {
        if (entry.index() < firstIndex()) {
            throw new IllegalArgumentException("index below firstIndex");
        }
        entries.add(entry);
    }

    @Override
    public LogEntry entryAt(long index) {
        LogEntry entry = entries.get((int) (index - baseIndex));
        if (entry == null) {
            throw new IllegalArgumentException("missing entry " + index);
        }
        return entry;
    }

    @Override
    public List<LogEntry> entriesFrom(long from) {
        if (from <= lastIndex() && from >= firstIndex()) {
            return List.copyOf(entries.subList((int) (from - baseIndex), entries.size()));
        }
        return List.of();
    }

    @Override
    public long firstIndex() {
        return baseIndex;
    }

    @Override
    public long lastIndex() {
        return entries.isEmpty() ? -1 : baseIndex + entries.size() - 1;
    }

    @Override
    public long lastTerm() {
        return entries.isEmpty() ? 0 : entries.get(entries.size() - 1).term();
    }

    @Override
    public long termAt(long index) {
        return entryAt(index).term();
    }

    @Override
    public int size() {
        return entries.size();
    }

    @Override
    public void truncateFrom(long index) {
        if (index <= baseIndex) {
            entries.clear();
            baseIndex = index;
            return;
        }
        if (index > lastIndex()) {
            return;
        }
        int from = (int) (index - baseIndex);
        entries.subList(from, entries.size()).clear();
    }

    @Override
    public void installSnapshot(long lastIncludedIndex) {
        if (lastIncludedIndex < firstIndex() - 1) {
            return;
        }
        int drop = (int) (lastIncludedIndex - baseIndex + 1);
        if (drop >= entries.size()) {
            entries.clear();
        } else if (drop > 0) {
            entries.subList(0, drop).clear();
        }
        baseIndex = lastIncludedIndex + 1;
    }

    @Override
    public void sync() {
        // 内存实现无需落盘
    }

    @Override
    public void close() {
        entries.clear();
    }
}
