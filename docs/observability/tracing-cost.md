# 企业级可观测性指南（ADR-0154）

## 追踪

```java
Tracer tracer = new Tracer(new TraceSampler(0.1), exporter);
Context ctx = tracer.start("gateway");
String header = tracer.inject(ctx);          // 跨 RPC 传播
Context remote = tracer.extract(header);
Context child = tracer.start("shard", remote);
tracer.end(child);
tracer.end(ctx);
```

- 嵌套跨度继承父上下文；跨 RPC 通过 header 传播；
- `end` 校验上下文匹配（防乱序结束）；
- 采样按 traceId 确定性（同一 trace 稳定采样）。

## 成本归因

`CostAttribution`：CostEntry(tenantId, domainId, cloud, resource,
cost)，按租户/云/域聚合，支持并发写入。

## 原则

追踪只观测，不修改事务/Raft 状态机。
