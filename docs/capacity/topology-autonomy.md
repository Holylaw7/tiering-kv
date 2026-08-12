# 拓扑感知联邦自治指南（ADR-0193）

## 模型

```text
地域 → 组（就近拓扑）
  → 组内 Q 平均 → 组间平均 → softmax 全局权重
```

## 使用

```java
TopologyFederatedAutonomy autonomy = new TopologyFederatedAutonomy();
autonomy.registerRegion("r1", "g0", 0.1, 0.0, 10.0);
autonomy.record("r1", Action.RELAX, reward);
Map<Action, Double> weights = autonomy.aggregate();
```

组级等权（拓扑感知）；只调权重/聚合策略，禁止放宽安全核心约束。
