# G0-5C-2B-4 迁移数据落点审计（只读）

- 状态：**审计完成，根因未定位**
- 基线：`0e73e49`；审计对象：`807b653` 以来的冷层迁移链路
- 方法：只读代码追踪 + B1 实测数据（`raw/f1/f1b3-requal.json`）

## 1. 完整调用链（含文件与行号）

```text
TrackingStorageEngine.put()            TrackingStorageEngine.java:31-36
  → delegate.put(...)                  // MemTable 计入 usedBytes
  → evictionManager.maybeEvict()       EvictionManager.java:105
      → 异步：migrationScheduler.submit(MigrationTask.pending(entry,"memory","cold"))
                                       EvictionManager.java:134
      → 同步：migration.migrate(entry)  EvictionManager.java:159（ColdMigration）
MigrationScheduler.submit(task)        MigrationScheduler.java:82-100
  → metrics.migrationSubmitted()       :89
  → log.append(PENDING)                :91
  → pool.execute(() -> run(task))      :97
MigrationScheduler.run(task)           :167-221
  → log.append(RUNNING)                :176
  → cold.put(current.entry())          :177   ← 目标写入
  → wal.append(WALEntry.delete(...))   :178
  → memTable.removePhysicalIfVersion() :179
  → log.append(SUCCESS)                :181
  → success = true                     :182
  finally: metrics.migrationCompleted(success, elapsed)  :212
ColdStorageEngine.put(entry)           ColdStorageEngine.java:219-229
  → pending.put(...)                   :222
  → pendingBytes += entry.size()       :223
  → if (pendingBytes >= 4MB) flushPendingLocked()  :225-227
```

批量路径：`submitBatch` → `runBatch` 直接 `cold.writeTable(entries)`（`:232`），**不经过 pending**。

## 2. 数据读取与写入位置（结论）

| 问题 | 结论 | 证据 |
| --- | --- | --- |
| 迁移读取来源 | `MigrationTask.entry()`（提交时从 `memTable.getEntry(key)` 取的快照），含完整 key/value | `EvictionManager.java:125`、`:134` |
| 目标写入调用 | `ColdStorageEngine.put(KeyValueEntry)` | `MigrationScheduler.java:177` |
| 目标组件 | 就是 `Main` 创建的 `ColdStorageEngine` | `Main.java:73`、`:86`、`:93`、`:95` |
| 是否可能写错实例 | **否**：同一个 `cold` 引用贯穿 `TieringController`（`:86`）、`ColdMigration`（`:93`）、`MigrationScheduler`（`TieringController` 内部构造） | 构造链核验 |

## 3. `migration_success` 的准确语义

递增位置：`MigrationScheduler.java:212`（`finally`），参数 `success` 在 `:182` 置真。

它成立的前提是**以下三步都未抛异常**：

1. `cold.put(entry)` 返回（数据进入 pending 或触发落表）；
2. `wal.append(delete)` 成功；
3. `memTable.removePhysicalIfVersion(...)` 完成。

**它表示"目标写入调用 + 源数据清理"已完成，不表示数据已持久化为 SSTable**（pending 中的数据仍在内存）。
名称里的 success 容易被读成"迁移持久化成功"，这是语义歧义。

## 4. 与 B1 数据的矛盾（未解释）

B1 实测（`raw/f1/f1b3-requal.json`）：

| 观测 | 值 |
| --- | --- |
| `migPending` | 0 → 1（T1） |
| `migOk` | 1（T2 0 s 起） |
| `pending_entries` / `pending_bytes` | **全程 0 / 0** |
| `cold_sstable_count` / `cold_disk_usage` | **全程 0 / 0** |
| 25 MB 值读回 | 26,214,400 字节完整 |

按代码路径，`cold.put` 之后 pending 至少应有 1 条；若迁移的是 25 MB 大值，`pendingBytes` 会越过 4 MB 而立即落表。
**两种可能都与"pending=0 且 tables=0"矛盾。**

### 候选解释（均未验证，禁止据此下结论）

| # | 假设 | 如何证伪 |
| --- | --- | --- |
| H1 | 迁移任务的目标 `cold` 与 INFO 读取的 `cold` 不是同一实例 | 打印两者 `System.identityHashCode` |
| H2 | `flushPendingLocked()` 执行后 pending 清空、表未进入 `tables` 列表（异常被吞/写入其他结构） | 审计 `flushPendingLocked` 的异常处理与 `tables` 维护 |
| H3 | 迁移的是某个小键，且该键随后被 compaction 清理（不应影响计数） | 记录被迁移键与大小 |
| H4 | 指标刷新器读取的是另一个冷层实例 | 核对 `StorageGaugeRefresher` 构造参数 |

## 5. 数据正确性风险（基于现有代码）

| 风险 | 判断 | 说明 |
| --- | --- | --- |
| 先删源、后写目标 | **否** | 顺序是 put → WAL delete → memTable remove（`:177-179`） |
| 目标失败但源已不可读 | **否** | 失败时 `success=false`，`memTable.removePhysicalIfVersion` 未执行（异常中断） |
| 写入错误实例 | **未发现** | 构造链核验为同一实例；仍是 H1 待证伪项 |
| 异步任务提前报成功 | 部分 | `success=true` 在 `cold.put` 返回后设置，但"持久化"未包含 |
| 重试重复计数 | 低 | 每次重试只在最终 `finally` 计一次 |

## 6. 定级

按评审给定的分类表：

| 分类 | 是否命中 |
| --- | --- |
| 迁移写入其他合法冷层组件 | 未发现 |
| 写入正确目标但 INFO 统计另一实例 | **待验证（H1/H4）** |
| `migration_success` 在目标写入前递增 | 否，在 `cold.put` 之后 |
| 目标写入失败仍递增 | 否，异常会走 FAILED |
| 源数据删除后目标写入失败 | 否 |
| 目标写入成功但 pending 不变 | **命中，需要进一步追踪** |

**定级：观测盲区 + 未定位的数据流矛盾（不是已确认的生产缺陷，也不能当作正常）。**

## 7. 结论与建议

1. 撤回"迁移只写入 pending、未达 4 MB 未落表"的解释（B1 已证伪）；
2. 实例身份、调用顺序、成功语义已查清，**矛盾出在 `cold.put` 之后到指标可读之间**；
3. 下一步只能做**受控诊断**（需批准，因为要改代码）：在迁移完成处记录被迁移键、大小、`cold` 身份哈希与 `pendingCount()`；
4. 在定位之前：
   - G0-5C 性能资格**保持 NOT QUALIFIED**；
   - 不构造 100k 冷键；不调阈值；不重定义 `migration_success`。

## 8. 证据等级

| 结论 | 等级 |
| --- | --- |
| 调用链与行号 | E1（代码直读） |
| 实例同一性 | E1（构造链直读，未运行期验证） |
| `migration_success` 语义 | E1 |
| B1 pending=0 与代码路径矛盾 | E2（实测数据 + 代码对照） |
| 根因 | **未定级**（假设未验证） |
