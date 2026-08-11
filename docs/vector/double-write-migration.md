# 向量双写迁移

Phase 31 · ADR-0134

## 流程

```text
beginMigration → put 双写（primary + secondary）
  → search 合并 → commitSwitch（停止双写）/ rollback（清 secondary）
```

## 能力

- 迁移窗口写入不丢失（双写）；
- 查询跨双写窗口合并 topK；
- 删除同时作用于双写目标；
- 回滚清空 secondary，主库不受影响。

## 基准（进程内）

双写搜索 100/1000 向量 × 100 次 ≈15–40ms。
