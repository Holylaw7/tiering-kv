# 强化学习自治指南（ADR-0180）

## 模型

```text
Q(a) ← Q(a) + lr × (reward - Q(a))
choose：epsilon-greedy
weights：softmax(Q)，总和恒为 1
```

## 使用

```java
ReinforcementAutonomy autonomy =
        new ReinforcementAutonomy(0.1, 0.0, 10.0);
Action action = autonomy.chooseAction();
autonomy.record(action, reward);
Map<Action, Double> weights = autonomy.weights();
```

只调整策略权重，禁止放宽安全核心约束。
