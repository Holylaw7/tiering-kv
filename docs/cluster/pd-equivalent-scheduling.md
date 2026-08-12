# PD 等价调度指南（ADR-0205）

## 放置

```java
PlacementScheduler placement = new PlacementScheduler();
placement.registerNode(new Node("n1", "az-1"));
placement.place("r1", "az-1", epoch); // epoch 不匹配拒绝
```

## 均衡

```java
List<Move> moves = rebalance.plan(loads, maxLoad);
// 超载节点 → 低载节点迁移
```

## 限流

```java
QuotaScheduler quota = new QuotaScheduler(limit);
quota.tryAcquire(); // CAS 精确限流
```

调度策略生产化，不放宽一致性约束。
