# 全球自治自学习围栏指南（ADR-0165）

## 学习规则

- 连续成功达到 successThreshold → 温和放宽（relaxStep）；
- 连续失败达到 failureThreshold → 收紧（tightenStep）；
- 回滚 → 立即熔断（circuitOpen），resetCircuit 恢复；
- 参数变化被 Bounds 上下界钳制。

## 使用

```java
SelfLearningFence fence = new SelfLearningFence(
        new Params(10, 5, 5),
        new Bounds(1, 20, 1, 10, 1, 8),
        1, 1, 2, 2);
fence.recordSuccess();
fence.recordFailure("prewrite failed");
fence.recordRollback("migration failed");
fence.audit(); // 参数变化审计
```

只调整策略参数，禁止放宽安全核心约束。
