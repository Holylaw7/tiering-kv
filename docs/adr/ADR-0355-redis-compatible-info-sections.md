# ADR-0355：Redis 兼容 INFO 标准 section

## Status

Accepted（2026-10-23）

## Context

Web 控制台需要展示真实运行态（内存占用、键数量、连接数、QPS、
运行时长、版本）。现状：

- `MetricsRegistry.infoText()` 只输出 `connections / active_requests /
  qps / avg_latency_ms / errors` 五个自研键，无 `# Memory`、`# Keyspace`，
  控制台无法取得内存与键空间指标；
- 内存真相在 `MemoryManager`、键数真相在 `StorageEngine.size()`，
  二者都不在 INFO 链路上；
- 若控制台自行"估算"指标，则违反"单一数据源"原则（ADR-0344）。

## Decision

新增 `io.tieringkv.observability.ServerInfoProvider`，由 `Main` 注入
`MetricsRegistry` + `MemoryManager` + 键数 supplier，输出 Redis 兼容
的标准 section：

| section | 关键字段 |
| --- | --- |
| Server | redis_version、tieringkv_version、tcp_port、uptime_in_seconds、run_id、jdk_version |
| Clients | connected_clients、active_requests |
| Memory | used_memory(_human)、maxmemory(_human)、used_memory_jvm(_human)、max_jvm_heap(_human) |
| Stats | 兼容旧键 connections/active_requests/qps/avg_latency_ms/errors，另加 instantaneous_ops_per_sec、total_keys |
| Keyspace | db0:keys=N,expires=0,avg_ttl=0 |

约定：

1. **向后兼容**：旧键必须保留，既有调用方与测试不回归；
2. **section 可单独寻址**：`INFO memory` 等通过 `sections()` 暴露，
   与 `ObservabilityRegistry.infoSections()` 合并后交给 `InfoCommand`；
3. **不引入热路径开销**：本 provider 仅在 INFO 请求时读取快照，
   命令执行路径零改动；
4. `ServerVersion` 优先读 MANIFEST 的 Implementation-Version，
   本地开发回退到源码常量。

## Alternatives

- **控制台侧估算**：把 JVM 内存当 used_memory——口径错误，且与
  `maxmemory` 语义冲突，否决；
- **新增 `MEMORY USAGE` 等命令**：命令面已冻结 157 项，新增命令
  成本高且与本需求（展示）不匹配，否决；
- **在 `CommandEngine` 内埋点统计每命令调用**：需要在 100 万 QPS
  热路径上做 map 查找，否决（见 ADR-0349 锁自由读评估的同类取舍）。

## Consequences

- 控制台可直接解析标准 INFO，无需私有协议；
- `INFO` 文本体积从 ~120B 增至 ~700B，属可接受范围；
- `MetricsRegistry.infoText()` 保留但不再被 `Main` 使用，仍供既有
  测试与集群侧复用。

## Implementation

- `src/main/java/io/tieringkv/observability/ServerInfoProvider.java`
- `src/main/java/io/tieringkv/ServerVersion.java`
- `src/main/java/io/tieringkv/Main.java`（接线）
- `src/test/java/io/tieringkv/observability/ServerInfoProviderTest.java`（6 用例）
