# G0-5B-2 证据：Gauge 刷新与写路径解耦（F3）

- 关联：ADR-0371、G0-5A F3
- 代码基线：`01716f9`

## 1. 改动范围

| 文件 | 改动 |
| --- | --- |
| `StorageMetrics.java` | 新增不可变 `ColdSnapshot` 记录，`publishColdSnapshot(...)` 整份替换；`snapshot()` 只读一次引用 |
| `StorageGaugeRefresher.java`（新增） | 单线程守护线程、500 ms 周期、CAS 防重入、失败保留旧快照并计数、幂等关闭 |
| `TieringController.java` | `onWriteCompleted()` 只更新内存原子值；合并事件改为 `requestRefresh()`；`refreshGauges()` 只做 O(1) 内存更新；`close()` 关闭刷新器 |
| `Main.java` | `ServerInfoProvider` 的 `refreshGauges` 传 `null`：INFO 不触发、不等待 |
| `ColdStorageEngine.java` | 新增 `tablesSnapshotCalls()` 诊断计数（验证写路径不再复制清单） |
| `StorageMetrics` 由 `final` 改为可继承 | 仅为测试注入"发布失败"场景 |

## 2. 验收项对照

| 验收项 | 证据 |
| --- | --- |
| 写路径不再 `tablesSnapshot()` / 按表求和 | `WritePathGaugeDecouplingTest.写路径不调用tablesSnapshot`：1000 次 `onWriteCompleted()` 后计数增量为 **0** |
| 独立单线程、固定周期、禁止重叠 | `StorageGaugeRefresherTest.刷新不会重叠执行`（4 线程并发调用，flush 供应商未并发进入）；线程名 `storage-gauge-refresher` |
| 不可变对象一次性发布 | `快照整份发布而不是逐字段更新`；`StorageMetrics.ColdSnapshot` 单引用替换 |
| 刷新失败保留旧快照 | `刷新失败保留上一份快照并计数`（失败计数 1、成功计数 0、旧快照不变） |
| INFO 不等待、不触发 | `INFO读取不等待刷新`（刷新被阻塞时 `snapshot()` < 50 ms 返回）；`Main` 传 `null` |
| 关闭幂等 / 关闭时在跑 | `线程启动后会周期性刷新且关闭幂等`（连续三次 close 不抛异常）；`close()` 中断并 `join(1s)` |
| F1 与 F3 互不影响 | 两个独立线程与独立失败计数；F1 测试与 F3 测试分别通过 |

## 3. 测试

| 类别 | 结果 |
| --- | --- |
| 新增 `StorageGaugeRefresherTest` | 6 / 6 |
| 新增 `WritePathGaugeDecouplingTest`（真实 `TieringController` + 真实 `ColdStorageEngine`） | 2 / 2 |
| 既有 `TieringControllerTest` | 断言前补一次 `refreshOnce()`（异步语义变更，断言数值未改） |
| **全量内核回归** | **15,134 例，0 失败，13 跳过** |

## 4. 性能验证：**未执行**

本轮没有做 GET/SET P99、写入吞吐、INFO P99 的前后对照。原因同 F1：需要在同机、同 JVM、
同数据规模下分别跑修改前后的三轮采样，本会话时间不足。

**因此只声称"写路径不再执行 O(表数) 的复制与求和"（由结构 + 计数断言保证），
不声称吞吐或 P99 有提升。** 这是 G0-5 关闭前需要补的验证项之一。

## 5. 残余风险

| 项 | 说明 |
| --- | --- |
| 冷层指标陈旧 | 最长约 500 ms + 单次刷新耗时 |
| 后台复制的成本 | 每次刷新仍复制一次表清单（发生在后台线程）；表数极多时需观测耗时（`lastDurationNanos` 可读） |
| 线程生命周期 | 关闭最多等待 1 秒；若刷新卡在冷层锁上，join 超时后线程会被中断退出 |
| 未做长稳 | 未观察长时间运行下的刷新频率与 GC |

## 6. 结论

**PASS WITH RESIDUAL RISKS**：F3 的结构目标（写路径不再复制表清单、INFO 不触发刷新）已达成并可验证；
性能资格验证与 F1 的 10 万冷键对照一并留作 G0-5 关闭前的待办。
