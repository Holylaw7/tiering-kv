package io.tieringkv.storage.cache;

/** 淘汰策略接口：Sampled-LFU（B2 默认）、LFU、Segment-LFU 与 ARC 可插拔（ADR-0012）。 */
public interface EvictionPolicy {

    String name();

    /**
     * 是否依赖上层在每次读/写路径构造并派发 {@link AccessEvent}。
     * 原地内联元数据策略（如 {@link SampledLfuPolicy}）返回 {@code false}，
     * 使 {@link TrackingStorageEngine} 在热路径零分配直通。
     */
    default boolean tracksViaAccessEvents() {
        return true;
    }

    /** 处理访问事件：更新热度 / 队列 / ghost。DELETE 与 EVICT 移除对应状态。 */
    void onAccess(AccessEvent event);

    /** 返回当前最优淘汰候选；无候选返回 null。 */
    EvictionCandidate selectCandidate();
}
