# Task: Phase 1 — RESP 协议

状态：⏳ 未开始

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
