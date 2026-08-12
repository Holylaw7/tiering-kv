# Raft Term 联动选主指南（ADR-0145）

## 动机

仅健康探测的选主无法防止分区/旧纪元节点自封 leader。
`RaftAwareLeaderSelector` 引入 term 单调约束。

## 使用

```java
RaftAwareLeaderSelector selector = new RaftAwareLeaderSelector(
        Map.of("r1", new RegionState(5, true),
               "r2", new RegionState(4, true)), "r1");
String leader = selector.selectLeader();
boolean ok = selector.tryBecomeLeader("r2", 6); // 低 term 拒绝
selector.updateRegion("r2", 6, true);
```

## 规则

- term 只增不减；
- 候选 term < currentTerm 拒绝自封；
- 自动选主优先健康且 term 等于最大 term 的地域；
- majorityHealthy 提供仲裁兜底。
