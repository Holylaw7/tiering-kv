# Task: Phase 1 — RESP 协议

状态：✅ 已完成（2026-08-09）

## 目标

实现 RESP2 编解码、基础命令集与 Netty 网络接入。

## 交付物

- protocol：RESP2 Encoder / Decoder、错误语义；
- command：命令注册表与分发（SET / GET / DEL / PING / ECHO / EXISTS）；
- network：Netty 事件循环接入（tcp / connection）；
- 单元 + 集成测试；
- docs/design/protocol-design.md 细化；ADR（RESP2 vs RESP3）视需要新增。

## 验收

- redis-cli / redis-benchmark 冒烟通过；
- 错误响应符合 RESP 语义；
- 热点 GET P50 建立基线。

## 关联

- ADR-0003；docs/architecture/network-architecture.md；
  docs/design/protocol-design.md。

## 验收结果

- `mvn test`：47 用例全绿（Decoder 13 / Encoder 8 / RequestParser 5 /
  CommandEngine 11 / 集成 9 / 延迟冒烟 1）。
- 协议冒烟：集成测试以真实 TCP 模拟 redis-cli，覆盖 PING / SET / GET / DEL /
  ECHO / EXISTS、未知命令、参数错误、pipeline、inline、二进制安全、8 并发连接。
- 延迟基线（本机回环，顺序往返 5000 次）：
  P50=0.064ms，P95=0.151ms，P99=0.216ms，约 12666 ops/s。
- 已知限制：inline 命令暂无引号解析；本机未安装 redis-cli，冒烟由集成测试等效验证。
