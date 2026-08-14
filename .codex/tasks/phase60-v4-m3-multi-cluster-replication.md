# Phase 60 — v4.0 M3 Multi-Cluster Replication Wiring

## Context

v4.0 M3（RFC-0001 / ADR-0321）：联邦一致性验证器（模拟）→ 真实
跨集群复制接线。基线：v4.0 M1/M2 完成并归档。

## Goal

1. RPC 消息类型 REPLICATION / REPLICATION_RESPONSE（34/35）
2. ReplicationEventCodec：ChangeEvent ↔ byte[]（长度前缀 + CRC32C）
3. LwwConflictResolver：timestamp + cluster id 决策 + seq 幂等
4. CrossClusterSink：目标端 LWW 决策 + StorageEngine 应用
5. CrossClusterReplicationChannel：复用 MultiRaftEndpoint RPC 收发
6. E2E：单写一致 / 双写 LWW 收敛 / 重复幂等 / 一致性验证接线
7. 全量回归 0 failures + 基准

## 交付

| 模块 | 文件 |
| --- | --- |
| 通道 | replication/cross/CrossClusterReplicationChannel.java |
| 编码 | replication/cross/ReplicationEventCodec.java |
| 冲突 | replication/cross/LwwConflictResolver.java |
| 应用 | replication/cross/CrossClusterSink.java |
| RPC | cluster/rpc/RpcMessageType.java（+34/35） |
| 测试 | replication/cross/*Test.java + E2E |
| 基准 | docs/benchmark/phase60-multi-cluster-replication-report.md |

## Test Plan

- Codec：roundtrip、损坏拒绝、空值
- LWW：时间戳胜出、同时间戳 cluster 序、DELETE 决策、seq 幂等
- Sink：PUT/DELETE 应用、被裁决丢弃不落盘
- Channel：双 endpoint E2E 单写一致、双写收敛、重复事件幂等
- 一致性验证：真实复制事件喂入 FederationConsistencyVerifier
- 全量回归 0 failures；新增测试 ≥50

## 验收

- ADR-0321 已批准（本文档引用）
- 本地全量回归 0 failures；真实 Runner 门禁 6/6
- 基准报告输出复制吞吐
- Conventional Commit 拆分提交
