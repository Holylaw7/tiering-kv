package io.tieringkv.monitor;

/** 容量模型（Goal 8）：分片 × 存储 × QPS → 节点估算。 */
public final class CapacityPlanner {

    public record CapacityEstimate(int nodes, long storageGB,
                                   long qps) {
    }

    public CapacityEstimate estimate(int shards, long storageGB,
                                     long qps, long storagePerNodeGB,
                                     long qpsPerNode) {
        int nodesByStorage = (int) Math.ceil(
                (double) storageGB / storagePerNodeGB);
        int nodesByQps = (int) Math.ceil(
                (double) qps / qpsPerNode);
        int nodes = Math.max(1, Math.max(nodesByStorage, nodesByQps));
        return new CapacityEstimate(nodes, storageGB, qps);
    }
}
