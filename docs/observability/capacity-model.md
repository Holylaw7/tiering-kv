# 容量模型

Phase 30 · Goal 8

`CapacityPlanner.estimate(shards, storageGB, qps, storagePerNode, qpsPerNode)`
按存储与 QPS 双维度取节点上限。

```text
CapacityEstimate(nodes, storageGB, qps)
```

基准：10K 次估算 ≈1ms。

## 使用

```java
CapacityPlanner.CapacityEstimate estimate =
        new CapacityPlanner().estimate(8, 1000, 1_000_000,
                100, 100_000);
// nodes = max(ceil(1000/100), ceil(1M/100K)) = 10
```
