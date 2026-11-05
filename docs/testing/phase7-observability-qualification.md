# Phase 7：Tiering Observability Qualification

- 日期：2026-11-05
- 范围：`INFO # Tiering` / `# Stats` 延迟分位 / Compaction 计数 / 控制面解析 / 前端三态展示
- 结论：**4 项工程细节全部验证通过，生命周期 12 项断言全绿，指标可追溯到真实内核状态**

## 0. 结论速览

| 验收项 | 结果 |
| --- | --- |
| 生命周期（空实例 → 写入 → Flush → 冷读 → Compaction → 重启） | **12/12 通过** |
| 性能影响（INFO 轮询 50 次/秒 vs 无轮询） | **无可测差异**（p99 0.537→0.503 ms） |
| 数据契约（单位/缺失/累计 vs 瞬时/解析） | **通过**（含 3 例单测） |
| 多实例隔离（local-6379 vs demo-tiering） | **各 9/9 项与自身内核一致，且两实例数值不同** |
| 前端三态（失败 / 未上报 / 零值） | **可区分**（红条 / 虚线提示 / 真实 0） |

## 1. refreshGauges() 的性能边界

**代码事实**（`TieringController.refreshGauges`）：

| 读取项 | 实现 | 成本 |
| --- | --- | --- |
| `memory.usedBytes()` | `AtomicLong.get()` | O(1) |
| `memTable.size()` | `liveSize` 计数器 | O(1) |
| `cold.tablesSnapshot()` | `List.copyOf(tables)`，持冷层锁 | O(表数)，**表数为个位数，锁内只做拷贝** |
| 冷层磁盘用量 | 累加 `SSTableMeta.fileSize`（Manifest 元数据） | O(表数)，**不做目录扫描** |

**额外约束**：新增 `refreshGaugesThrottled()`，200 ms 内重复 INFO 直接复用上次 gauge，
INFO 路径只用节流版本；**一次渲染只刷新一次、只取一次快照**（见第 2 节）。

**实测**（`scripts/phase7-perf.mjs`，GET+SET 串行，4 秒）：

| 场景 | 吞吐 | p50 | p95 | p99 |
| --- | --- | --- | --- | --- |
| 基线（无 INFO 流量） | 11,452 ops/s | 0.135 ms | 0.331 ms | 0.537 ms |
| 叠加 50 次/秒 `INFO` | 12,061 ops/s | 0.134 ms | 0.325 ms | 0.503 ms |

`INFO` 自身耗时（200 次）：p50 **0.246 ms** / p95 **0.509 ms** / max 3.286 ms。

> 结论：在当前数据规模下（≤ 8 张 SSTable）INFO 与 gauge 刷新对业务延迟无可见影响；
> 成本随表数量线性增长，而表数受 Compaction 阈值约束，不存在"越跑越慢"的问题。

## 2. 指标快照一致性

问题：`# Memory` 与 `# Tiering` 若各自读取 gauge，会出现"内存 3.1 MB / MemTable 3.4 MB"这类自相矛盾。

修复：`ServerInfoProvider.render()` 在渲染开始时 **刷新一次 gauge 并取一个 `StorageMetrics.Snapshot`**，
`# Memory.used_memory` 与 `# Tiering` 全部字段都来自这同一个快照；渲染结束后置空，避免跨请求复用陈旧数据。

派生字段 `tiering_cold_share` 也由该快照内的 hot/cold 计算，不会出现"分子分母来自不同时刻"。

## 3. 多实例隔离

`scripts/phase7-isolation.mjs`：直连两个内核读 `INFO`，同时调用控制面
`/dashboard/overview`，逐字段比对：

| 实例 | 端口 | 校验项 | 失败 | 快照（entries / sstables / coldBytes / flush / compaction / cacheHits） |
| --- | --- | --- | --- | --- |
| local-6379 | 6379 | 9 | 0 | 520 / 0 / 0 / 0 / 0 / 0 |
| demo-tiering | 6380 | 9 | 0 | 47,260 / 5 / 431,823,300 / 1 / 0 / 0 |

两个实例快照不同（`instancesDistinct=true`），且每个字段都与**它自己**的内核 `INFO` 完全一致。

前端层面：`.instance-select` 切换后，指标带、存储分层、图表、终端会话标题全部跟随当前实例
（终端会话标题为 `会话 · <实例名>`，历史记录按实例 ID 存储）。

## 4. 重启与缺失/零值状态

### 4.1 生命周期断言（`scripts/phase7-lifecycle.ps1`，12/12）

```
PASS 空实例上报 Tiering 段落
PASS 空实例 MemTable 为 0            entries=0
PASS 空实例无 SSTable                sstables=0
PASS 空实例 Flush/Compaction 为 0    flush=0 compaction=0
PASS 写入后 MemTable 增长            entries=60000 bytes=6348890
PASS 触发 Flush 并生成 SSTable       flush=8 sstables=1 cold=138335328
PASS 冷读产生 BlockCache 命中/未命中  hits=1393 misses=28607
PASS 触发 Compaction                 compaction=1
PASS 重启后仍上报 Tiering 段落
PASS 重启后冷层数据保留               before=1 after=2 cold=276647395
PASS 重启后累计计数从零重建           flush=1 compaction=0
PASS 重启后数据仍可读（DBSIZE>0）     keys=1599996
```

### 4.2 三条重要语义（已写入验收）

1. **累计计数 vs gauge**：`flush/compaction/migration/cache` 是"自启动累计"，重启归零；
   `memtable/cold_sstable/cold_disk` 是 gauge，重启时由 WAL 重放 + Manifest 重建。
2. **重启本身可能触发一次 Flush**：WAL 重放会把 MemTable 填回水位以上，恢复期 Flush 是正常行为
   （实测 `before=1 → after=2`），因此断言写成"冷层数据保留"而非"表数不变"。
3. **缺失 ≠ 零**：`INFO` 没有 `# Tiering` 段时，控制面 `tieringReported=false`，
   前端显示"内核未上报"，不显示 0。

### 4.3 前端三态

| 状态 | 触发条件 | 界面表现 |
| --- | --- | --- |
| **请求失败 / 实例离线** | `live` 为空或实例状态 DOWN | 指标值显示 `—`、说明"实例离线"、分层区红色提示条 |
| **内核未上报** | `tieringReported=false` | 分层区虚线提示"INFO 中无 # Tiering 段" |
| **真实零值** | `tieringReported=true` 且计数为 0 | 正常渲染 0，占比条为空轨道并标注"实例为空" |

截图：`docs/screenshots/v2/24-state-normal.png`、`25-state-zero.png`、`26-state-offline.png`。

### 4.4 Flush / Compaction 进行中

`FlushScheduler.inProgress()` 与 `ColdStorageEngine.isCompacting()` 以 gauge 形式进入
`INFO`（`tiering_flush_in_progress` / `tiering_compaction_in_progress`），
前端在"存储分层"标题右侧显示脉冲标记「Flush 进行中 / Compaction 进行中」。

## 5. 复现命令

```powershell
# 性能
cd console-web; node scripts/phase7-perf.mjs 6380 4000 50
# 隔离
node scripts/phase7-isolation.mjs
# 生命周期（会自行起停 6381 上的实例）
pwsh -NoProfile -File scripts/phase7-lifecycle.ps1
# 三态截图
node scripts/phase7-ui-states.mjs   # 需要已注册 demo-tiering / phase7-empty
```

## 6. 键分布（TD-098，本阶段补充）

`# Tiering` 新增三个字段，全部来自表元数据，成本 O(表数)：

| 字段 | 含义 | 精确性 |
| --- | --- | --- |
| `tiering_hot_keys` | 热层键数（MemTable 存活条目） | **精确** |
| `tiering_cold_entries` | 冷层表条目累加（Σ `SSTableMeta.entryCount`） | **上界**（含历史版本/墓碑） |
| `tiering_hot_key_share` | 热层键占（热 + 冷条目）比例 | 派生 |

前端在"存储分层"下方渲染 **存储构成** 面板：容量条（字节）与键条（计数）两行，
显式标注"冷层为表条目累加（含历史版本），非去重精确值"，页脚给出**去重后的键总数**（DBSIZE 口径）。

### 冷层精确计数（DBSIZE）的成本实测

`GET`/`SET` 不触发冷层扫描；只有**冷层写入/合并后**的第一次 `size()` 才触发一次全量迭代：

| 调用 | 耗时 |
| --- | --- |
| 冷层变更后首次 `DBSIZE` | **17.1 ms**（6 张表 / 597 MB / 230 万键） |
| 随后 `DBSIZE` | 0.63 ms / 0.39 ms |
| `INFO`（缓存命中） | 0.68 / 0.65 / 0.54 ms |

> 结论：最坏一次 17 ms，且只在冷层变更后发生一次；5 秒采集周期下可接受。
> 若冷层规模继续增长，可改为后台异步刷新（记为后续优化项）。

## 7. 已知边界

- 延迟分位是**分桶估算**（桶上界，且以实测 max 封顶），不是精确分位。
- `tiering_cold_entries` 是**上界**（历史版本未去重），与"键总数（去重）"不同，界面已标注。
- 冷层精确计数（DBSIZE 口径）在冷层变更后首次调用需要一次全量迭代（实测 17 ms）。
- `refreshGaugesThrottled` 的 200 ms 窗口意味着 gauge 最多滞后 200 ms（对 5 秒采集周期无影响）。