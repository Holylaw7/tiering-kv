# ADR-0345: Tracing Wiring (W3C traceparent) + Replication/Multi-Model Feeding

## Status

Accepted

## Context

P3 增量（ADR-0344 记录，本阶段落实）：

- 复制管线（ReplicationPipeline/BidirectionalPipeline）已有
  LagTracker / replicated / suppressed / conflicts 计数，但未接入
  `ReplicationMetricsRegistry`（INFO/Prometheus 只显示零值）；
- 多模型命令（JsonCommand/MultiModelCommand/TimeSeriesCommand）无
  `MultiModelMetricsRegistry` 喂数（JSON 写/校验失败/TS 写/字节）；
- ADR-0154 Tracer 为自研 `traceId:spanId` 格式，未接生产路径，
  未兼容 W3C traceparent（OTel 标准透传头）。

## Decision

### 1. 复制管线喂数（additive）

- `ReplicationMetricsRegistry.attachLagTracker(LagTracker)`；
- `ReplicationPipeline` / `BidirectionalPipeline` 增加可选
  `ReplicationMetricsRegistry` 构造（旧构造不变）：
  - ReplicationPipeline：成功复制 → `recordReplicated`，冲突 →
    `recordConflict`，并 attach 内部 LagTracker（水位可见）；
  - BidirectionalPipeline：广播成功 → `recordReplicated`，
    环回抑制 → `recordSuppressed`，LWW 冲突 → `recordConflict`。

### 2. 多模型命令喂数（additive）

- `JsonCommand` / `MultiModelCommand` / `TimeSeriesCommand` 增加
  可选 `MultiModelMetricsRegistry` 构造；
- `CommandRegistry` 新增
  `createDefaultWithVectorAndMetrics(infoProvider, sections,
  VectorCollectionRegistry, MultiModelMetricsRegistry)`（旧重载不变）；
- 喂数点：JSON.SET 成功 → jsonWrites + 值字节；JSON 解析失败 →
  jsonValidationErrors；TS.ADD / TS.INCRBY 成功 → tsWrites + 值字节；
  向量/JSON/TS 写入均累加多模型字节。

### 3. W3C traceparent 透传（零依赖，扩展点保留）

- `Tracer` 增加 W3C traceparent 支持（不引入 OTel SDK）：
  - `startW3c(operation, parent)`：生成 32hex traceId / 16hex spanId；
  - `injectTraceparent(context)` → `00-<trace32>-<span16>-01`；
  - `extractTraceparent(header)`：校验 version/长度，非法抛异常；
- 新增 `TracingMetricsRegistry`（observability）：从 `TraceExporter`
  snapshot 统计 span 数 / 平均 / 最大延迟；
- `ObservabilityRegistry` 增加 `tracing` INFO section；
  `MetricsExporter` 新增 5 参 `exportAll`（旧 4 参保留，tracing 为
  null 时跳过）；
- 生产接入：`GatewayRuntime` 增加可选 Tracer 重载，serve 每条命令
  start/end span（同步路径，ThreadLocal 栈同线程匹配）；
- 后续扩展点：OTLP 导出器、异步命令路径 span（需要跨线程栈或
  Context 传递）、跨 RPC traceparent 帧扩展。

## Alternatives

1. 引入 opentelemetry-api / agent：依赖重、需后端与 agent 配置，
   与现有轻量体系不匹配；
2. 只做内存 span 不导出：无法被采集器消费；
3. 跳过 tracing：P3 可观测性收口不完整。

## Consequences

优点：

- W3C traceparent 标准透传格式（OTel 兼容），零新增依赖；
- 复制/多模型指标从零值变为真实数据（INFO + Prometheus 口径一致）；
- GatewayRuntime 命令 span 形成最小可观测闭环。

缺点：

- 非真实 OTLP 后端（需后续适配器）；
- Tracer ThreadLocal 仅适合同步路径（异步命令路径需后续改造）。

风险：

- extract 校验需严格（非法头拒绝而非静默）；
- CommandRegistry 重载数量增加（保持旧 API 兼容即可控）。

## Implementation

`ReplicationMetricsRegistry`（attach）、`ReplicationPipeline` /
`BidirectionalPipeline`（可选构造）、`JsonCommand` /
`MultiModelCommand` / `TimeSeriesCommand`（可选构造）、
`CommandRegistry`（新重载）、`observability/tracing/Tracer`
（W3C）、`observability/TracingMetricsRegistry`、
`ObservabilityRegistry`（tracing section + 5 参构造）、
`MetricsExporter`（5 参 exportAll）、`GatewayRuntime`（可选 Tracer）；
对应单测（复制喂数、多模型喂数、W3C traceparent、tracing section）。
