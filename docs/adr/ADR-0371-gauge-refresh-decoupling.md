# ADR-0371：冷层 Gauge 刷新与写路径解耦（G0-5B-2 / F3）

- 状态：Accepted
- 日期：2026-11-07
- 关联：G0-5A F3、ADR-0370（F1）

## 背景

`TieringController.onWriteCompleted()` 每个写命令都调用 `refreshGauges()`，后者复制一次 SSTable 清单
并按表求和（`tablesSnapshot()` 持冷层锁）。写路径因此承担 O(表数) 的成本，SSTable 越多写越慢。

## 决策

### 1. 写路径只更新原子值

`onWriteCompleted()` 只做两件事：

- `metrics.setMemoryGauges(...)`（3 个 `AtomicLong`，O(1)）；
- 水位判断（原有逻辑，不变）。

不再复制表清单、不再求和、不再读取冷层状态。

### 2. 独立刷新器（不与 F1 合并）

新增 `StorageGaugeRefresher`：

| 属性 | 实现 |
| --- | --- |
| 线程 | 单线程守护线程 `storage-gauge-refresher`，固定 500 ms 周期 |
| 防重入 | `AtomicBoolean` CAS，重入直接返回（不排队、不阻塞） |
| 触发 | 周期 + `requestRefresh()` 标记（合并事件/兼容入口用） |
| 失败 | 计数 + 告警日志，保留上一份有效快照 |
| 关闭 | 幂等；中断线程并 `join(1s)`；统计字段可读 |

F1 的 `cold-live-count-refresher` 与 F3 的 `storage-gauge-refresher` **各自独立**：
任一扫描失败都不影响另一个，耗时互不阻塞。

### 3. 不可变快照整份发布

`StorageMetrics` 增加 `ColdSnapshot` 记录（表数、磁盘占用、条目数、flush/compaction 标记、刷新时间），
由 `publishColdSnapshot(...)` **一次引用替换**；`snapshot()` 只读一次引用，因此 INFO 不会读到
"表数是新的、磁盘占用是旧的"这种组合。

`used_memory*` 仍由写路径实时更新（O(1)），**没有**被搬进后台。

### 4. INFO 不触发、不等待

`ServerInfoProvider` 的 `refreshGauges` 参数在 `Main` 中传 `null`：INFO 只读最近一次快照，
不执行任何刷新；快照陈旧上限 = 刷新周期（500 ms）+ 单次刷新耗时。

## 后果

- 写路径不再随 SSTable 数量增长；
- 冷层指标最多滞后 500 ms；
- 每次刷新仍要复制一次表清单（发生在后台线程，不占请求线程）；
- 刷新失败时 INFO 显示上一份快照，不显示错误值；失败次数可读。
