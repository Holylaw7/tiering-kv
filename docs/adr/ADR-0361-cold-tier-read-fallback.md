# ADR-0361：冷层读回退与 DBSIZE/SCAN 统计口径

## Status

Accepted（2026-10-28）

## Context

端到端验证（`docs/benchmark/tiering-verification-findings.md`）发现 TD-086：
迁移只把数据写进冷层（`cold.put`），主读链
（`Tracking → HotKey → WAL → MemTable`）从不查询冷层。后果是
**"超配即丢数据"**：

- 60 万键写入后仅 **51.92%** 可读回，288,461 个键返回 nil；
- 冷层已落盘 199.8 MB（两个 SSTable），但 `GET` 永远只查内存；
- `DBSIZE`/`SCAN` 只统计热层，与用户看到的数据量不一致。

这使"冷热分层"这一核心卖点不成立，也让此前与 Redis 的对比中
"3M 键 GET 57,787 rps" 的数字失真（含大量直接 miss）。

## Decision

1. **读回退**：新增 `TieringReadStorageEngine`（`AbstractStorageDecorator` 子类），
   置于读链最外层：
   - `get/exists`：热层未命中时查询冷层；
   - `delete`：热层删除后给冷层写墓碑，防止旧值复活；
   - `size`：热层 + 冷层存活键数；
   - `iterator`：两层按 key 无符号字典序归并，**热层优先**去重。
2. **原子写 copy-up**：`INCR/APPEND/GETSET/GETDEL/UPDATE` 之前，若键仅在冷层，
   先把其值与剩余 TTL 回填热层再执行；顺序为"先写热层、后删冷层"，
   中途失败不会丢数据。`SETNX` 遇冷层键视为存在而拒绝。
3. **TTL 语义**：`TTL/EXPIRE/PERSIST` 在热层缺失时读取冷层条目的
   `expireTimestamp`，返回真实剩余时间或执行回填后再设置。
4. **计数口径**：冷层 `liveCount()` 采用**脏标记 + 惰性全量计数**
   （写入置脏，查询时迭代一次）。理由：键可能经 `put` 或 `writeTable`
   进入冷层，逐条维护计数需在整表写入时做 N 次历史查询，代价过高。

## Alternatives

- **只改 DBSIZE 不改读路径**：数据仍然读不回来，否决；
- **读命中即回填热层**：读操作会触发写压力与背压，热层可能被读请求打满，否决
  （列为后续可选策略）；
- **在 MemTable 保留墓碑索引**：内存开销随删除量增长，且不解决回填需求，否决。

## Consequences

- 迁移后数据可读，`DBSIZE` 精确等于实际键数（实测 60 万 = 60 万）；
- 冷读延迟接近热读（P50 0.104 vs 0.099 ms，page cache 命中），
  但首次冷读受 BlockCache 冷启动影响；
- `DBSIZE` 在写入后首次查询会触发一次冷层全量扫描（60 万键约 0.2–0.5s），
  控制台 5 秒采集一次时可接受；后续可加 TTL 缓存或增量计数优化；
- `SCAN` 覆盖冷层后，大键空间首次扫描成本上升；
- `WATCH`/`versionOf` 仍只反映热层版本，事务守卫对冷层键暂不生效（已记录）。

## Implementation

- `storage/cold/ColdStorageEngine.java`（`entry()` / `liveCount()`）
- `storage/tiering/TieringReadStorageEngine.java`
- `storage/tiering/UnionStorageIterator.java`
- `Main.java`（接线到读链最外层）
- 测试：`ColdStorageEngineEntryTest`(5)、`TieringReadStorageEngineTest`(7)、
  `TieringAtomicCopyUpTest`(8)
- 验证：`scripts/verify-tiering.mjs`（60 万键 100% 命中）、
  `scripts/capacity-probe.mjs`（每键 ≈ 值 + 74B）
