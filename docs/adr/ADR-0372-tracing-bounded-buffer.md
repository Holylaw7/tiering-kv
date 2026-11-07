# ADR-0372：Tracing 有界缓冲与聚合缓存（G0-5B-3 / F2）

- 状态：Accepted
- 日期：2026-11-07
- 关联：G0-5A F2、ADR-0154（追踪）

## 背景

`TraceExporter` 用无界 `CopyOnWriteArrayList` 保存 Span，且 `TracingMetricsRegistry.snapshot()`
每次 INFO 都调用 `spans()` 复制整个列表并两次遍历。后果：

1. 内存随进程运行时间无界增长；
2. INFO 的 tracing 段成本随 Span 总量增长；
3. `CopyOnWriteArrayList` 每次写入都复制数组，写入越频繁越贵。

## 决策

### 1. 有界缓冲：丢最旧

- 默认容量 **1024**，可构造时指定；
- 满载时淘汰**最旧** Span，新 Span 正常进入；
- 每次淘汰 `dropped` 加一，不静默丢弃。

先确认过用途：Span 仅用于观测与 JSON 导出（`toJson`），没有可靠交付或审计语义，
因此丢最旧可接受。若后续需要完整审计，需另设持久化通道，而不是扩大缓冲。

### 2. 聚合改为增量 + O(1) 快照

`TraceExporter` 维护累计值：`totalExported`、`dropped`、`durationSum`、`maxDuration`，
`snapshot()` 直接返回 `{retained, totalExported, dropped, avg, max, updatedAt}`，**不遍历缓冲**。

### 3. 语义澄清（必须写清，避免误读）

| 指标 | 语义 |
| --- | --- |
| `tracing_spans` | **当前缓冲区保留数**（≤ 容量） |
| `tracing_spans_total` | 进程启动以来**累计导出数** |
| `tracing_dropped` | 因容量上限被淘汰的数量 |
| `tracing_capacity` | 缓冲容量 |
| `tracing_avg_duration_nanos` / `max` | **累计**均值/最大值，不受淘汰影响 |

旧实现中 `tracing_spans` 等于"累计收集数"（因为无界）；新实现等于"保留数"，
并新增 `total`/`dropped` 两个字段保留原有总量信息。INFO 字段只增不减，兼容既有解析。

### 4. 并发

- 缓冲用 `ArrayDeque` + `synchronized`，写入与淘汰在同一临界区，**不会超容量**；
- 计数器用 `LongAdder`/`AtomicLong`，在锁外更新；
- `spans()` 返回副本，消费者不会读到"已被淘汰的对象"（副本持有引用，对象本身不可变）；
- 无后台线程，因此没有关闭遗留问题。

## 后果

- 内存有上界（约 1024 × Span 大小）；
- INFO 的 tracing 段为 O(1)；
- 丢弃的 Span 不再可查，只能通过 `tracing_dropped` 观测；
- `tracing_spans` 数值含义变化（保留数而非累计数），已在指标名旁新增总量字段。
