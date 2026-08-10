# ADR-0034: Production Service Lifecycle

## Status

Accepted

## Context

生产运行需要明确的启动/关闭顺序，保证：配置生效、存储一致、请求不丢、
WAL 完整。

## Decision

定义生命周期：

```text
启动：load config → init storage（WAL recover + 冷层 Manifest）→ start workers
      → start network
关闭（SIGTERM）：stop accept → drain active requests（有界等待）→ flush 响应
      → WAL force → checkpoint → close storage/executors → exit
```

1. `TieringConfig`：YAML 加载（config/application.yaml）+ 默认值，启动打印
   生效配置；
2. `ShutdownManager`：幂等关闭；排空超时（默认 5s）后强制关闭；
3. WAL force + checkpoint 在存储关闭前执行，保证重启恢复；
4. 指标（ADR-0032 关联）：MetricsRegistry 记录连接/活跃请求，供排空判断。

## Alternatives

1. 直接 System.exit：不排空、WAL 可能未 force；
2. 无超时排空：连接挂死导致无法退出。

## Consequences

**优点：** 可预测停机、数据一致、可运维（SIGTERM/SIGINT）。
**缺点：** 停机等待 ≤ 超时；需要测试覆盖。
**风险：** 排空与写入竞态 → 停机标记后拒绝新命令（-SHUTDOWN）。

## Implementation

- `config/TieringConfig`、`ShutdownManager`、`MetricsRegistry`；
- Main 注册 shutdown hook；GracefulShutdownTest 验证请求不丢、重启恢复。
