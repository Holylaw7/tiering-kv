# Async Commit & Resolved Timestamp 指南（ADR-0209）

## 使用

```java
AsyncCommitCoordinator coordinator = new AsyncCommitCoordinator();
CommitResult result = coordinator.commit("t1", regionCount);
// 单区 onePhase=true；多区回退 2PC

ResolvedTimestampService service = new ResolvedTimestampService();
service.advance(ts); // CAS 单调推进
```

单区一阶段降低延迟；resolved-ts 保证跨区一致性读水位。
