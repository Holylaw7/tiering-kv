# G0-5B-3 证据：Tracing 有界缓冲与聚合缓存（F2）

- 关联：ADR-0372、G0-5A F2
- 代码基线：`5f6bd7f`

## 1. 实现前审计结论

| 问题 | 结论 |
| --- | --- |
| Span 用途 | 仅观测与 `toJson()` 导出（`ContinuousAuditPipeline`），**无可靠交付/审计语义** |
| 消费者 | `TracingMetricsRegistry`（INFO/Prometheus）、`toJson()`；测试读取 `spans()` |
| 旧结构 | `CopyOnWriteArrayList`，无界 |
| 旧 INFO 成本 | `spans()` 全量复制 + 两次流式遍历（`TracingMetricsRegistry.java:21-31`） |

因此允许"丢最旧"，不需要另建可靠通道。

## 2. 改动

| 文件 | 改动 |
| --- | --- |
| `TraceExporter.java` | `ArrayDeque` 有界缓冲（默认 1024，可配置）；满载淘汰最旧并 `dropped++`；累计 `totalExported`/`durationSum`/`maxDuration`；`snapshot()` O(1)；`spans()` 返回有界副本 |
| `TracingMetricsRegistry.java` | 改为读 `exporter.snapshot()`，不再遍历；新增 `tracing_spans_total`、`tracing_dropped`、`tracing_capacity` |
| `TraceExporterBoundedTest.java`（新增） | 9 例 |

字段语义（写入 ADR-0372）：

| 字段 | 语义 |
| --- | --- |
| `tracing_spans` | 当前缓冲保留数（≤ 容量） |
| `tracing_spans_total` | 累计导出数 |
| `tracing_dropped` | 因容量淘汰数 |
| `tracing_capacity` | 缓冲容量 |
| `tracing_avg/max_duration_nanos` | **累计**均值/最大值，不受淘汰影响 |

## 3. 验收项对照

| 验收项 | 证据 |
| --- | --- |
| 缓冲容量固定上限 | 默认 1024；`容量必须为正` |
| 淘汰策略明确（丢最旧） | `达到上限后淘汰最旧并计数`：写入 t0..t4 后保留 `t2,t3,t4` |
| 长期运行内存有界 | `持续高负载下容量不超限且丢弃计数准确`：10,000 次写入、容量 64、保留 64、丢弃 9,936 |
| 聚合不在写入路径遍历 | `snapshot()` 只读累加器；`聚合快照为O1且不遍历缓冲` |
| INFO 只读缓存 | `INFO指标不遍历缓冲且包含新字段` |
| 并发不超容量、计数不重复 | `消费者读取与淘汰并发安全`：4 写 2 读、共 8,000 次写，容量始终 ≤16，`total=8000`、`dropped=8000-size` |
| 生命周期 | 无后台线程；`clear()` 只清缓冲、不清计数 |
| 既有 tracing 行为 | `TracerTest` 24 例、`ObservabilityRegistryTest` 9 例全通过 |

## 4. 回归

**全量内核：15,143 例，0 失败，13 跳过。**

## 5. 性能验证：**未执行**

未测 Span 写入吞吐/延迟、INFO tracing 段 P50/P99、淘汰延迟、长时间内存曲线。
因此只声称"结构上有界 + INFO 为 O(1) 读取"，不声称性能提升。

## 6. 残余风险

| 项 | 说明 |
| --- | --- |
| 数据丢失 | 满载后最旧 Span 不可恢复，只能靠 `tracing_dropped` 观测 |
| 语义变化 | `tracing_spans` 由"累计数"变为"保留数"；总量改由 `tracing_spans_total` 提供。若有外部看板按旧语义绘制，需要同步调整 |
| 容量选择 | 1024 为经验默认值，未按实际 trace 产生速率评估 |
| 锁 | 写入与淘汰共用一把锁；高并发采样时是潜在热点，未压测 |

## 7. 结论

**PASS WITH RESIDUAL RISKS**：无界增长已消除，INFO tracing 段为 O(1)；
但"tracing_spans 语义变化"需要外部看板同步，且性能资格仍未验证。
