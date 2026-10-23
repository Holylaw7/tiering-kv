# ADR-0357：控制台 RESP 客户端、指标采集与保留

## Status

Accepted（2026-10-23）

## Context

控制面每秒可能发起数十条命令（SCAN 分页、终端、采集），需要：
连接复用、超时控制、错误分类、二进制安全；同时要把采集到的指标
持久化供趋势图使用，并防止无限增长。

## Decision

**1. 自研 RESP 客户端（不引第三方 Jedis/Lettuce）**

- `RespCodec`：命令编码为 bulk string 数组；应答支持 `+ - : $ *`，
  可递归解析嵌套数组；
- `RespClient`：单连接同步调用，`soTimeout` 控制读超时；
- `RespClientPool`：按实例维度缓存空闲连接（上限 4），借出时校验可用性，
  异常连接立即丢弃重建；
- 二进制安全：值不是合法 UTF-8 时以 `base64:` 前缀返回，避免破坏 JSON。

**2. 指标采集**

- `MonitorService` 每 `console.metrics.interval-ms`（默认 5s）对启用实例
  执行 `INFO`，解析 server/clients/memory/stats/keyspace 五个 section；
- 采集结果写入 `metric_point`（单行多列，避免 EAV 模型的查询放大）；
- 采集失败只把实例标记 DOWN，不抛出到调度线程。

**3. 保留策略**

- `metric_point` 默认保留 72 小时，`op_log` 保留 30 天；
- `RetentionService` 每小时执行一次批量删除（`delete ... where < :before`）。

## Alternatives

- 直接引入 Jedis：省事，但与"从零实现协议层"的项目定位冲突，且多一层
  版本耦合；本项目已有 RESP 编解码经验（ADR-0006），自研成本可控；
- 指标存时序库（Prometheus/InfluxDB）：答辩演示需要额外组件，收益有限；
  单机 MySQL 在 5s 粒度下每日约 1.7 万行/实例，完全可承载。

## Consequences

- 控制面与内核之间只有 RESP 一种耦合方式；
- 指标精度受采集周期限制，仪表盘展示"采集周期 5 秒"以免误读；
- 需要维护 RESP 客户端的协议兼容性（现有 157 条命令，控制台只用到
  INFO/SCAN/TYPE/TTL/GET/HGETALL/LRANGE/SMEMBERS/ZRANGE 等只读命令，
  以及终端透传的任意命令）。

## Implementation

- `console-server/src/main/java/io/tieringkv/webconsole/resp/`
- `.../service/MonitorService.java`、`MetricMapper.java`、`RetentionService.java`
- 测试：`RespCodecTest`（10 用例）、`InfoParserTest`（5）、`MetricMapperTest`（3）
