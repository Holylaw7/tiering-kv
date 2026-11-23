package io.tieringkv.cluster.raft.log;

import io.tieringkv.cluster.raft.LogEntry;

import java.util.List;

/** Raft 日志抽象（ADR-0039）：追加/读取/截断/快照压缩。 */
public interface RaftLog extends AutoCloseable {

    void append(LogEntry entry);

    /** 读取指定索引条目；已被快照压缩或不存在时抛出异常。 */
    LogEntry entryAt(long index);

    /** 读取 [from, lastIndex] 闭区间条目；from 超出范围返回空列表。 */
    List<LogEntry> entriesFrom(long from);

    /** 当前日志最小索引（快照压缩后 >0）。 */
    long firstIndex();

    long lastIndex();

    long lastTerm();

    long termAt(long index);

    int size();

    /** 删除 [index, lastIndex] 区间（Raft 冲突覆盖/截断）。 */
    void truncateFrom(long index);

    /** 快照压缩：删除 lastIncludedIndex 及更早条目。 */
    void installSnapshot(long lastIncludedIndex);

    /** 按耐久策略强制落盘。 */
    void sync();

    @Override
    void close();
}
