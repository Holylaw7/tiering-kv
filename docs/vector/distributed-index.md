# 分布式向量索引

Phase 29 · ADR-0121

## 架构

```text
VectorShardManager
  ├─ VectorShard（按 id hash 路由）
  ├─ RebalancePlanner（倾斜 → 迁移计划）
  └─ search：跨分片 topK 合并
```

## 能力

- 水平扩展（分片数可配）；
- 重平衡计划（excess → room）；
- 查询合并（排序取 topK）；
- CDC 增量（Phase 30 深化）。

## 基准（进程内）

100/1000 向量 × 100 次 topK=5 ≈25–35ms。

## 限制

- 重平衡为计划生成（真实迁移接 Migration 待 Phase 30）；
- 分片键为 id hash，无范围分片。
