# ADR-0008: Memory Concurrency Model

## Status

Accepted

## Context

MemTable 必须支撑高并发读写。候选模型：

- Global Lock：简单，但所有操作串行，无法利用多核；
- 全局 RWLock：读并行，但单写者瓶颈仍存在，且热点写全部竞争；
- Striped Lock（分段锁）：不同 key 进入不同段，竞争随段数摊薄；
- Lock Free：无锁读写吞吐最高，但实现与正确性验证成本高。

同时需要满足：禁止整个 MemTable 加 synchronized；100 线程并发测试必须稳定。

## Decision

采用 **64 段 Striped Lock（ReentrantReadWriteLock）**：

1. key 经 FNV-1a 哈希定位段（`hash & 63`），不同 key 大概率进入不同段；
2. 读路径（GET / EXISTS / 迭代采集）持有段读锁，可并行；
3. 写路径（PUT / DELETE / 主动过期）持有段写锁，同一段内串行；
4. 跨段操作只有全局迭代器：逐段读锁采集后归并，不持有全局锁；
5. 内存压力回调在释放锁之后触发，避免回调内再写同段造成死锁；
6. TTL 主动清扫使用独立后台线程，逐段短暂加写锁，不阻塞主请求路径。

## Alternatives

1. Global Lock：实现最简单，但被并发目标否决；
2. 全局 RWLock：读并行但写集中竞争，热段问题无法隔离；
3. Lock Free SkipList：Phase 7 候选（届时新 ADR），当前正确性风险与验证成本
   过高，不满足"先正确后极致"的交付节奏。

## Consequences

**优点：** 无全局锁；读读并行、写写按段并行；实现与调试成本可控。
**缺点：** 热点 key 集中在同一段时仍竞争；迭代器为"分段快照"（弱一致）。
**风险：** 段间数据一致性与迭代语义 → 以"每键只属于一段 + 版本号"约束保证；
Phase 7 通过 metrics 观测段竞争并评估无锁演进。

## Implementation

- `MemTable.SEGMENT_COUNT = 64`；`Segment { SkipList list; ReentrantReadWriteLock lock; }`；
- 版本号全局单调（`Version`），支持 TTL 守卫与未来 WAL 排序。
