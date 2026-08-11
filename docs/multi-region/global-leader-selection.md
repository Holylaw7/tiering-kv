# 全局多活自动选主

Phase 32 · ADR-0143

## 选择器

```text
LeaderSelector(health, initialLeader)
  ├─ selectLeader()：当前主健康保持，否则选首个健康地域
  └─ majorityHealthy()：多数健康判定（防脑裂）
```

## 语义

- 单故障切换（3 节点多数保持）；
- 双故障多数丢失（不再选主）；
- 仲裁与 Raft term 兜底防脑裂。
