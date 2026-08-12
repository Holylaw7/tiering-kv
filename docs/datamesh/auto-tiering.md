# 远端物化自动分层指南（ADR-0187）

## 规则

```text
accessCount >= hotThreshold  → HOT
accessCount >= warmThreshold → WARM
否则                          → COLD
```

## 使用

```java
AutoTierManager manager = new AutoTierManager();
manager.recordAccess("v1");
Tier tier = manager.decide("v1", 100, 10);
manager.resetCounts(); // 周期重置热度
```

阈值参数化验收；分层保持 stale 语义与主权约束。
