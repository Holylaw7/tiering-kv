# ADR-0356：Web 控制台作为独立控制面进程

## Status

Accepted（2026-10-23）

## Context

项目需要一套可交付的"前后端"运维平台：登录鉴权、实例纳管、指标看板、
键空间浏览、在线终端、告警、备份、审计。需要先决定它与内核的关系。

候选：

1. **嵌入内核**：在内核进程内启动 HTTP 服务，直接调用内存中的
   `StorageEngine`、`MetricsRegistry`；
2. **独立控制面进程**：新增 Spring Boot 服务，通过 TCP + RESP 访问内核；
3. **纯前端直连 RESP**：浏览器直连 6379（需 WebSocket 网关，仍需后端）。

## Decision

采用方案 2：`console-server`（Spring Boot 3.3 / JDK 21）作为独立控制面，
`console-web`（Vue 3）作为展示面，内核保持零改动（仅 INFO 增强，ADR-0355）。

关键约束：

1. **协议边界**：控制面不依赖内核 jar，只通过 RESP 协议交互，等价于
   任意 Redis 客户端；内核因此可独立部署、可被第三方客户端访问；
2. **业务数据自持**：用户、角色、审计、告警规则、指标历史落在独立
   MySQL 8 库（`tiering_console`），不污染内核存储；
3. **控制面可替换**：内核崩溃或升级不影响控制面历史数据；控制面重启
   不影响数据面服务；
4. **故障隔离**：控制面的连接池、超时、重试策略与内核的背压、淘汰策略
   互相独立，避免"管理面拖垮数据面"。

## Alternatives

- 嵌入内核：违背"协议即边界"的既有架构（ADR-0002/ADR-0006），且把
  HTTP 线程模型引入 100 万 QPS 的数据面事件循环，否决；
- 纯前端直连：浏览器无法直接说 RESP，且需要把实例口令下发到前端，
  安全模型不可接受，否决。

## Consequences

- 部署多一个进程与一套 MySQL，但可通过 Docker Compose 一键拉起；
- 指标存在采集延迟（默认 5s），控制台明确标注"采集周期"，不假装实时；
- 控制面需要自己实现 RESP 客户端与连接池（约 400 行，见 ADR-0357）。

## Implementation

- `console-server/`（82 个 Java 文件）
- `console-web/`（Vue 3 + TS + Element Plus + ECharts）
- `deploy/console/docker-compose.yml`（内核 + 控制面 + 前端 + MySQL）
