# 策略风险自适应加固指南（ADR-0190）

## 使用

```java
AdaptiveHardener hardener = new AdaptiveHardener();
int revoked = hardener.harden(policy, riskThreshold, audit);
int restored = hardener.rollback(policy, audit);
```

## 语义

- 评分 ≥ 阈值 → 撤销全部白名单（deny + 审计）；
- 回滚重新允许被撤销 pair（allow + 审计）；
- 全程可追踪、可恢复。
