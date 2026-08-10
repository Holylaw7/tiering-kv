package io.tieringkv.cluster.raft.log;

/** RaftLog 耐久策略（ADR-0039）：与 WAL 的 ALWAYS/EVERY_SEC/NO 对齐。 */
public enum Durability {
    /** 每条 append 后立即 force，最强持久化。 */
    SYNC,
    /** 缓冲写入 + 周期 force（默认，存在 ≤1 个刷新周期丢失窗口）。 */
    ASYNC,
    /** 不 force，仅测试/原型。 */
    NONE
}
