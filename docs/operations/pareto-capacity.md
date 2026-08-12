# Pareto 多目标容量指南（ADR-0191）

## 支配关系

```text
A 支配 B ⟺ A.slo ≥ B.slo ∧ A.cost ≤ B.cost ∧ A.risk ≤ B.risk
            ∧ 至少一项严格更优
```

## 使用

```java
List<Candidate> front = optimizer.paretoFront(candidates);
Candidate chosen = optimizer.chooseByWeights(front,
        wSlo, wCost, wRisk);
```

Pareto 前沿可解释；权重选择最大化 wSlo×slo - wCost×cost - wRisk×risk。
