# ADR-0009: TTL Management Strategy

## Status

Accepted

## Context

SET 需要支持过期时间（`EX` 秒 / `PX` 毫秒）。过期键若不清理会永久占用内存；
若清理过频则消耗 CPU 并产生锁竞争。候选：

- Lazy Expiration：访问时检查，实现简单、无后台开销，但过期键长期滞留内存；
- Active Expiration：后台周期扫描，及时回收内存，但全表扫描成本高；
- Hybrid：惰性检查 + 有索引的主动清理。

## Decision

采用 **Hybrid（惰性 + 主动）**：

1. **惰性**：GET / EXISTS / 迭代在访问时检查 `expireTimestamp`，过期即视为
   不存在（Redis 语义）；
2. **主动**：`TTLManager` 维护按过期时间排序的 min-heap（仅在带 TTL 写入时
   入队），后台单线程周期（默认 1s）弹出到期项；
3. **版本守卫**：清除前校验 entry 的 version 与 expireTimestamp 一致，避免
   误删"已重新设置 TTL"的新数据；
4. **过期即物理移除**（回收内存）；DEL 仍使用 tombstone（ADR-0007 语义，
   WAL 阶段会把两类删除统一落盘）；
5. 后台线程为 daemon，逐段短暂加写锁，不阻塞主请求路径。

## Alternatives

1. 纯 Lazy：实现最简单，但过期键永不回收，MemoryManager 配额会被击穿；
2. 纯 Active 全表扫描：内存回收及时，但扫描成本随数据量线性增长；
3. 惰性 + 随机采样（Redis 默认）：实现省内存，但回收不及时；min-heap 方案
   对本项目更确定，且为 WAL 提供精确过期序列。

## Consequences

**优点：** 访问语义正确 + 内存及时回收（O(logN) 每次过期）；版本守卫保证安全。
**缺点：** min-heap 每 TTL 写入需 O(logN) 入队；多一个后台线程。
**风险：** 时钟回拨/跳跃 → 以单调时钟为准，expire 判定使用 `now >= expire`
语义；Phase 4 WAL 回放时需按同规则重建 TTL 队列。

## Implementation

- `storage/memory/TTLManager` + `MemTable.expireIfMatches`；
- `SET key value EX seconds | PX milliseconds`（命令层解析）；
- 测试用可注入时钟（`TimeSource`）与手动 `activeExpire()`。
