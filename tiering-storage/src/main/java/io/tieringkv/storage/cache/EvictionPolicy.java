package io.tieringkv.storage.cache;

/** 淘汰策略接口：LFU（默认）与 ARC（原型）可插拔（ADR-0012）。 */
public interface EvictionPolicy {

    String name();

    /** 处理访问事件：更新热度 / 队列 / ghost。DELETE 与 EVICT 移除对应状态。 */
    void onAccess(AccessEvent event);

    /** 返回当前最优淘汰候选；无候选返回 null。 */
    EvictionCandidate selectCandidate();
}
