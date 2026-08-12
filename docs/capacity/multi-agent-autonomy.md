# 多智能体自治指南（ADR-0186）

## 模型

```text
每地域：本地 Q（epsilon-greedy）
  → 周期联邦聚合：Q 平均 → softmax 全局权重
```

## 使用

```java
MultiAgentAutonomy autonomy = new MultiAgentAutonomy();
autonomy.registerRegion("r1", 0.1, 0.0, 10.0);
autonomy.record("r1", Action.RELAX, reward);
Map<Action, Double> weights = autonomy.aggregate();
```

只聚合 Q/权重，禁止放宽安全核心约束；聚合全程审计。
