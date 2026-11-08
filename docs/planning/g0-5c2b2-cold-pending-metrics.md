# G0-5C-2B-2 证据：冷层 pending 只读指标

- 目的：消除"迁移成功但冷层无 SSTable"的观测盲区，**不是** F1 性能资格
- 基线：`e4f55cb`

## 1. 实现前审计

| 问题 | 结论 |
| --- | --- |
| pending 结构 | `ColdStorageEngine.pending`（`TreeMap`）+ `pendingBytes`，均在 `lock` 内维护 |
| 条目进入时机 | `put()` 写入 pending 后累加 `pendingBytes`，超过 `pendingThresholdBytes` 调用 `flushPendingLocked()` |
| `migration_success` 语义 | 迁移任务完成计数，**不代表数据已落 SSTable**；B1 中 `migOk=1` 而 `coldTables=0` 即此中间态 |
| 落表触发 | `pendingBytes >= pendingThresholdBytes`（默认 4 MB）→ `flushPendingLocked()` 写 SSTable 并清空 pending |
| 失败/重试 | pending 不回滚；迁移失败只影响计数 |
| close/重启 | pending 只存在内存，未落表的数据不在 Manifest 中 |

## 2. 指标

| 指标 | 来源 | 复杂度 |
| --- | --- | --- |
| `tiering_cold_pending_entries` | `ColdStorageEngine.pendingCount()`（`pending.size()`） | O(1) |
| `tiering_cold_pending_bytes` | `ColdStorageEngine.pendingByteSize()` | O(1) |

两者经 `StorageMetrics.ColdSnapshot`（整份不可变快照）由 `StorageGaugeRefresher` 发布，
INFO 只读快照；**未新增线程、未改落表阈值、未改 Flush/Eviction 顺序**。

## 3. 测试（5 例，真实冷层）

| 用例 | 结果 |
| --- | --- |
| pending 为空时为零 | ✅ |
| 条目进入后 count/bytes 增长 | ✅ |
| 达阈值落表后 pending 归零、SSTable +1 | ✅ |
| 刷新器把 pending 发布进 INFO 快照 | ✅ |
| INFO 侧读快照不触发 `tablesSnapshot()` | ✅ 50 次 `snapshot()` 后调用计数不变 |

## 4. 回归

**全量内核：15,152 例，0 失败，13 跳过。**

## 5. 边界

- 该指标只回答"冷层 pending 里还有多少"，**不证明** 100k 冷键资格；
- 迁移失败时 pending 不回滚，指标会包含失败任务的部分数据（既有行为，未改）；
- 未验证 4 MB 阈值之外的落表条件（如 close 时是否强制落表）。