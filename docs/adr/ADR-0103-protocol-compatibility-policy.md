# ADR-0103: Protocol Compatibility Policy

## Status

Accepted

## Context

Phase 26 进入 v1.0 发布冻结。客户端（RESP2）、RPC（TxnRpcCodec /
Raft RPC）、元数据命令（TxnMetaCommand）与存储格式（WAL/SSTable/
MVCC 索引）均已稳定，需要正式冻结版本号并建立兼容性保障，防止后续
additive 功能破坏既有客户端。

## Decision

1. 冻结协议版本：`RPC_VERSION=1`、`RESP_VERSION=2`、
   `STORAGE_FORMAT_VERSION=1`，由 `ProtocolVersion` 常量承载；
2. 冻结范围：Client API / RESP / RPC / Metadata Command / Storage
   Format；变更必须新增版本号并走 ADR 兼容性评审；
3. 新增 `ProtocolCompatibilityTest`：旧客户端 connect/read/write/
   transaction 序列持续可用；
4. 发布 `docs/api/compatibility-guide.md`、`protocol-version.md`、
   `upgrade-policy.md`。

## Alternatives

1. 不冻结、持续演进：破坏旧客户端，违背 v1.0 发布语义；
2. 每个命令独立版本：管理成本高，收益低。

## Consequences

优点：客户端升级成本可预期；协议回归有自动化测试。

缺点：冻结后新特性需兼容性评审，节奏变慢（v1.x 预期）。

风险：旧客户端对 CLUSTER/AUTH 子集行为可能误解，需在兼容性清单如实
标注。

## Implementation

代码影响范围：

- `protocol/ProtocolVersion`（版本常量）；
- `ProtocolCompatibilityTest`（RESP/RPC/Meta 兼容矩阵）；
- `docs/api/*`（三个发布文档）。
