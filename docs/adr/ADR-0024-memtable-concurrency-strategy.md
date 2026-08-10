# ADR-0024: MemTable Concurrency Strategy

## Status

Accepted

## Context

MemTable 当前为 64 段 SkipList + 分段 ReentrantReadWriteLock。并发压力下
段内竞争仍存在。候选：

- **Striped Lock（加大分段）**：64 → 256 段，降低碰撞概率，保持已验证语义；
- **ConcurrentHashMap / ConcurrentSkipListMap**：JDK 无锁读，但失去有序语义
  或需重写迭代/合并逻辑；
- **Copy-on-Write 快照读**：GET 无锁，但每次写发布快照 O(段大小)，写放大
  不可接受；
- **Lock-Free SkipList**：最优吞吐，但正确性验证成本极高，且本项目规则禁止
  "使用未经验证的 lock-free 算法"。

## Decision

采用 **256 段 Striped ReentrantReadWriteLock（Option A）**：

1. `SEGMENT_COUNT` 64 → 256（2 的幂，哈希掩码不变式保持）；
2. 读路径维持读锁（可并行），写路径单写者每段；
3. **不引入未验证 lock-free**：读放大收益不足以承担正确性风险；
4. 热点读的"低锁"诉求由 HotKeyReadCache（不可变缓存 + TTL + 写失效）承担
   （ADR-0025），即"热点子集无锁读、全量数据锁定读"；
5. 未来选项：验证过的并发跳表/快照结构 → 新 ADR（TD-015）。

## Alternatives

1. ConcurrentSkipListMap：改造成本高且自定义 Entry/版本语义丢失；
2. Copy-on-Write：写放大，被否决；
3. Lock-Free SkipList：正确性风险，暂缓（TD-015）。

## Consequences

**优点：** 正确性可证明（Phase 2 语义不变）；碰撞概率降低 4×。
**缺点：** 读仍持锁（轻量）；热点段仍可能倾斜。
**风险：** 段数增加的内存开销可忽略（仅锁对象）。

## Implementation

- `MemTable.SEGMENT_COUNT = 256`；
- `ConcurrencyMetrics` 观测队列/等待/延迟；
- HotKeyReadCache 提供热点子集无锁读（ADR-0025）。
