# ADR-0364：指标刷新节流、单快照一致性与三态展示

- 状态：Accepted
- 日期：2026-11-05
- 关联：Phase 7（Tiering Observability Qualification）、ADR-0362、ADR-0363

## 背景

把存储分层指标接出来之后，有四个工程细节必须收口，否则"数字好看但不可信"：

1. `refreshGauges()` 在每次 INFO 时执行，需要明确的成本边界；
2. `# Memory` 与 `# Tiering` 若各自取数，会出现同一份 INFO 内部数值互相矛盾；
3. 前端必须区分「请求失败」「内核未上报」「真实为零」三种状态；
4. Flush / Compaction 进行中的状态无法从累计计数推断。

## 决策

1. **节流刷新**：INFO 路径调用 `refreshGaugesThrottled()`，200 ms 内复用上次 gauge。
   gauge 读取全部是 O(1) 计数器 + O(表数) 的清单复制，**不做目录扫描、不持长锁**。
2. **单快照**：`ServerInfoProvider.render()` 开始时刷新一次并取一个 `StorageMetrics.Snapshot`，
   `# Memory.used_memory` 与整个 `# Tiering` 都从该快照取值；渲染结束清空，禁止跨请求复用。
3. **三态契约**：控制面 `LiveMetrics.tieringReported` 由「INFO 中是否存在
   `tiering_memtable_max_bytes`」决定；前端据此区分未上报；`live` 为空即"取不到"。
4. **进行中标记**：`FlushScheduler.inProgress()` 与 `ColdStorageEngine.isCompacting()`
   以 `tiering_*_in_progress` gauge 进入 INFO，前端显示脉冲标记。

## 影响

- `INFO` 增加 2 个 gauge 字段；`MetricsRegistry.Snapshot` 不变（延迟分位已在 ADR-0363）。
- 控制面 `LiveMetrics` 增加 `tieringReported / flushInProgress / compactionInProgress`。
- 前端指标带在离线时显示 `—`，分层区按三态渲染；空实例的占比条改为空轨道并标注。
- 验证脚本：`phase7-perf.mjs`、`phase7-isolation.mjs`、`phase7-lifecycle.ps1`、`phase7-ui-states.mjs`。
- 验收报告：`docs/testing/phase7-observability-qualification.md`。
