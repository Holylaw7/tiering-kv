# v1.0 API 兼容性指南

Phase 26 · 2026-08-11 · ADR-0103

## 1. 冻结契约

| 契约 | 版本 | 说明 |
| --- | --- | --- |
| RESP | 2 | 旧客户端 SET/GET/DEL/EXISTS/PING/ECHO 持续可用 |
| RPC | 1 | TxnRpcCodec / RaftMessageCodec wire 值冻结（1–31） |
| Metadata Command | 1 | TxnMetaCommand 五种类型 + UTF 状态直存 |
| Storage Format | 1 | WAL / SSTable / MVCC 索引 / PITR 日志格式冻结 |

`ProtocolVersion` 常量承载版本号；变更必须新增版本并走 ADR 兼容性评审。

## 2. 兼容性保障

- `ProtocolCompatibilityTest`（51 项）：旧客户端命令、pipeline、inline、
  二进制安全、RPC 往返、Meta 命令、消息类型 wire 值；
- 响应注入防护：错误/简单字符串 CR/LF 清洗；
- 大 value（≥64KB）在 RPC/快照路径均验证。

## 3. 已知子集

- CLUSTER SLOTS/MOVED 已提供；NODES/ASK 为下一版本方向（TD-038）；
- AUTH/ACL 接入点已设计（RBAC 模型），网关侧接线待 Phase 27。
