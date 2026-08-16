# ADR-0349: Lock-Free Read Assessment (TD-015 Closure)

## Status

Accepted

## Context

TD-015：全量无锁读（ABA / 内存回收 / 可见性）→ 暂缓，待验证后新 ADR。

现状：MemTable 使用分段 RWLock（Phase 2 ADR-0008，当前 256 段），
读路径已叠加 Hot Key Read Cache + Request Coalescing（Phase 7
ADR-0020/0021）；存储层 GET 基线 P99 ≈ 2.5μs，网络端到端
P99 ≈ 0.19ms，Key Sharding 下并发读吞吐 2.6–6.3M ops/s。

## Decision

**不实施全量无锁读，关闭 TD-015**：

1. 维持分段 RWLock + Hot Cache 组合（工程上已满足读放大与延迟
   目标，无实测瓶颈）；
2. 无锁 SkipList 需要处理 ABA / 内存回收（RCU/Hazard Pointer 或
   类似机制）/ 可见性，复杂度与回归风险与当前收益不成比例；
3. 若未来出现读多写多的高并发热点场景，先以 Hot Cache 扩容、
   分片细粒度化（段数 256 → 512/1024）与复制读扩展应对，再评估
   无锁结构；
4. 记录评估证据（ADR-0024/0008 基线 + 本 ADR），不遗留悬空 TODO。

## Alternatives

1. 引入 ConcurrentSkipListMap / lock-free SkipList：复杂性高、
   回收机制缺失风险大；
2. 段数继续细粒度化：零风险增量优化，已作为首选替代路径。

## Consequences

优点：技术债有明确结论与替代路径，不再悬空。

缺点：理论上存在锁竞争上限（当前无实测瓶颈）。

风险：低——本决策不改变任何运行时行为。

## Implementation

ROADMAP TD-015 标记关闭；最终收尾报告同步。
