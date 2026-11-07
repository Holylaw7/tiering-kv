# G0-5A：INFO 指标代码路径审计

- 阶段：G0-5A（只审计，不改代码）
- 代码基线：`f586d5b`
- 审计对象：`INFO` / `INFO <section>` 的完整调用链与字段来源

## 1. INFO 调用链

```text
Netty ChannelPipeline → CommandEngine → CommandRegistry:117
  map.put("info", new InfoCommand(infoProvider, sections))
    → InfoCommand.execute()            // 仅做 section 选择，无统计
      → ServerInfoProvider.render()    // 全量 INFO
        → prepareSnapshot()            // ① refreshGaugesThrottled() ② storageMetrics.snapshot()
        → sections(): server/clients/memory/stats/keyspace/tiering
          → ObservabilityRegistry.infoSections(): vector/replication/multimodel/backup/tracing
```

证据：`InfoCommand.java:35-51`、`CommandRegistry.java:117`、`Main.java:125-132`、
`ServerInfoProvider.java:88-114`、`ObservabilityRegistry.java:61-74`。

## 2. 字段数量与分级（静态统计）

| 段 | 字段数 | 主要来源 |
| --- | --- | --- |
| Server | 11 | 常量与 `System.getProperty` |
| Clients | 2 | `MetricsRegistry.snapshot()` |
| Memory | 9 | `Runtime` + `MemoryMXBean` + `StorageMetrics.Snapshot` |
| Stats | 12 | `MetricsRegistry.snapshot()` + `keys()` |
| Keyspace | 1 | `keys()` |
| Tiering | 24 | `StorageMetrics.Snapshot` + `CacheStatistics.Snapshot` |
| Vector | 7 | `VectorMetricsRegistry.metricLines()` |
| Replication | 5 | `ReplicationMetricsRegistry.metricLines()` |
| MultiModel | 4 | `MultiModelMetricsRegistry.metricLines()` |
| Backup | 6 | `BackupMetricsRegistry.metricLines()` |
| Tracing | 3 | `TracingMetricsRegistry.metricLines()` |
| **合计** | **84** | — |

分类计数（按最坏路径计）：

| 分类 | 数量 | 说明 |
| --- | --- | --- |
| O(1)（原子计数器 / 缓存值） | 71 | 直接读 `LongAdder`/`AtomicLong`/常量 |
| O(n)（随数据量增长） | 10 | `total_keys`、`db0`、tracing 3 项、replication 5 项 |
| IO（磁盘读取） | 3 | `total_keys`、`db0`（经 `cold.liveCount()`），以及由它带出的 keyspace 段 |
| BLOCKING（可能阻塞） | 4 | 上述 3 项 + `refreshGaugesThrottled` 内的 `tablesSnapshot()` 锁 |
| NEEDS_FIX | 4 组 | 见 §5 |

> 说明：`latency_*` 6 项为派生值，来自固定 17 桶直方图，按 O(1) 计；但每次 INFO 会**分配一个新直方图并做两次加锁合并**（见 §4）。

## 3. 字段级证据（关键项）

| 字段 | 来源 | 复杂度 | 证据 |
| --- | --- | --- | --- |
| `server.*`（11） | 常量 / `System.getProperty` / `ProcessHandle.current().pid()` | O(1) | `ServerInfoProvider.java:120-135` |
| `connected_clients`、`active_requests` | `LongAdder.sum()` | O(1)* | `MetricsRegistry.java:54` |
| `qps`、`avg_latency_ms` | 累计计数与纳秒和的派生 | O(1) | `MetricsRegistry.java:49-53` |
| `latency_p50/p95/p99/max/samples` | 17 桶直方图合并 | O(1)（17 桶） | `LatencyStats.java:45-67`、`LatencyHistogram.java:16-65` |
| `used_memory*`、`maxmemory*` | `StorageMetrics` 原子值 | O(1) | `ServerInfoProvider.java:150-153` |
| `used_memory_jvm*`、`max_jvm_heap*` | `MemoryMXBean` | O(1) | `ServerInfoProvider.java:146-148` |
| **`total_keys`** | `syncStorage::size` → `delegate.size() + cold.liveCount()` | **O(n) + IO** | `TieringReadStorageEngine.java:62-63`、`ColdStorageEngine.java:125-144` |
| **`db0`** | 同上，第二次调用 `keys()` | **O(n)（首次已缓存则 O(1)）** | `ServerInfoProvider.java:238-250` |
| `tiering_*`（24） | `StorageMetrics.Snapshot` + `CacheStatistics.Snapshot` | O(1) | `ServerInfoProvider.java:189-228` |
| `tiering_cold_sstable_count/disk_usage` | 由 `refreshGauges()` 在 INFO 内刷新 | O(表数) | `TieringController.java:100-110` |
| `vector_*`（7） | 原子/缓存字段 | O(1) | `VectorMetricsRegistry.metricLines()` |
| **`replication_*`（5）** | 遍历副本表计算最大延迟 | **O(副本数)** | `ReplicationMetricsRegistry.snapshot(now)` |
| `multimodel_*`（4） | `LongAdder` | O(1) | `MultiModelMetricsRegistry.metricLines()` |
| `backup_*`（6） | 累计计数 + 副本延迟 | O(副本数) | `BackupMetricsRegistry.snapshot()` |
| **`tracing_*`（3）** | `TraceExporter.spans()` 复制全量列表并两次求和 | **O(span 数)，且无上界** | `TracingMetricsRegistry.java:21-31`、`TraceExporter.java:12-23` |

*`LongAdder.sum()` 在小并发下接近 O(1)，但在高并发写入时需遍历 cell，属于"弱 O(1)"。

## 4. 锁、IO、遍历与刷新机制

### 4.1 INFO 线程内会执行的昂贵操作

| 操作 | 位置 | 代价 |
| --- | --- | --- |
| `cold.liveCount()` 全量迭代 | `ColdStorageEngine.java:125-144` | 冷层每个条目一次迭代（含磁盘读取），脏标记置位后首次查询触发 |
| `cold.tablesSnapshot()` 复制表列表 | `ColdStorageEngine.java:270-274` | O(表数) 复制 + 持 `lock` |
| `LatencyStats.snapshot()` 新建直方图并合并两个窗口 | `LatencyStats.java:45-53` | 每次 INFO 分配 17 个 `LongAdder`，两次 `synchronized` |
| `TraceExporter.spans()` 复制全量 span | `TraceExporter.java:21-23` | O(span 数) 复制（`CopyOnWriteArrayList`，无上限） |

### 4.2 Gauge 刷新：不是在后台，而是在 INFO 与写路径上

- `ServerInfoProvider.render()` 每次调用 `prepareSnapshot()` → `refreshGauges.run()`（`ServerInfoProvider.java:88-110`）；
- `refreshGaugesThrottled()` 只有 **200 ms** 节流（`TieringController.java:112-120`），节流窗口外仍由**请求线程**执行全量刷新；
- 更关键的是写路径：`TieringController.onWriteCompleted()` **每次写**都调用 `refreshGauges()`（非节流，`TieringController.java:122-128`），
  每次都会 `cold.tablesSnapshot()` 复制列表并按表求和 — 即 O(表数) 的开销被放到了**每个写命令**上。
- 后台刷新线程：**不存在**。全仓库未找到定时刷新 gauge 的调度器；指标要么随写更新，要么随 INFO 更新。

### 4.3 RESP 兼容性

- `INFO` 与 `INFO <section>` 都返回 `RespBulkString`（`InfoCommand.java:39-50`），RESP2/RESP3 下行为一致；
- section 名为小写匹配，未知 section 返回 `ERR unknown info section`；
- 字段为 `key:value\r\n` 文本，与 Redis 约定一致；控制面按行解析（第 4 章），本次审计未发现格式变化。

## 5. 已确认问题（NEEDS_FIX）

| 编号 | 问题 | 影响 | 证据 |
| --- | --- | --- | --- |
| **F1** | `INFO` 的 `total_keys` / `db0` 触发 `cold.liveCount()` 全量扫描 | 冷层数据多时单次 INFO 可达毫秒级甚至更高；与"INFO 不触发磁盘扫描"直接冲突 | `ColdStorageEngine.java:125-144` |
| **F2** | tracing 指标遍历**无上限**的 span 列表 | 进程运行越久 INFO 越慢，且 `CopyOnWriteArrayList` 复制成本随 span 增长 | `TraceExporter.java:12-23` |
| **F3** | 写路径每次 `onWriteCompleted` 都做 O(表数) 的 gauge 刷新 | 写吞吐随 SSTable 数量增长而下降（与 INFO 无关的隐性成本） | `TieringController.java:122-128` |
| **F4** | 每次 INFO 分配直方图 + 两次加锁合并；每条命令 `record()` 加一次 `synchronized(current)` | 高并发下 `current` 监视器成为写路径争用点；INFO 产生额外分配 | `LatencyStats.java:19-24, 45-53` |

## 6. 待验证问题（UNKNOWN）

| 项 | 说明 |
| --- | --- |
| `LongAdder.sum()` 在高并发下的实际耗时 | 未在 32 核以上压测，无法给出数字 |
| `cold.liveCount()` 在 10 万级冷键下的实测耗时 | 仅有历史 `DBSIZE` 17.1 ms（6 表 / 597 MB）的观测，未单独测 INFO 路径 |
| `replication`/`backup` 在副本数增长时的耗时 | 当前环境副本数 ≤ 3，无可信外推 |
| RESP3 客户端解析差异 | 仅验证了内核返回 bulk string，未用真实 RESP3 客户端逐字段比对 |

## 7. 建议修复顺序（G0-5B 输入）

1. **F1**：把 `cold.liveCount()` 改成后台维护的计数器或快照（写入时增量维护 + 定期校正），INFO 只读缓存；
   在此之前，至少让 INFO 使用"上次已知值 + 脏标记"，不在请求线程内全量计数。
2. **F3**：`onWriteCompleted` 改为节流刷新（与 INFO 共用 200 ms 节流），或把 gauge 刷新移到独立线程；
   这一项对写吞吐的影响可能大于 INFO 本身。
3. **F2**：给 `TraceExporter` 设上界（如 1024 span 环形缓冲），并缓存聚合值，INFO 读缓存。
4. **F4**：`LatencyStats.record()` 改为无锁或分段计数；`snapshot()` 复用直方图对象，避免每次分配。

## 8. 本阶段结论

**PASS WITH RISKS**。

- 84 个字段中 71 个为 O(1) 读取，INFO 的**主体**符合"低成本快照"原则；
- 但存在 4 组明确违背原则的路径：INFO 触发冷层全量扫描（F1）、无界遍历（F2）、
  写路径全量刷新（F3）、每命令加锁与每 INFO 分配（F4）；
- 尚未发现 INFO 触发数据库查询或网络 RPC；也未发现全局写锁；
- 本阶段未修改任何生产/测试/配置代码，`git diff` 仅包含本报告。
