# Phase 60 Review — v4.0 M3 Multi-Cluster Replication Wiring

## 总体结论

v4.0 M3（ADR-0321）完成：联邦一致性验证器（模拟）升级为真实跨集群
复制接线——RPC 通道、LWW 冲突决策、目标端水位持久化、本集群复制
管线串联、分区/恢复混沌。全量回归 **14640 tests / 0 failures**（本地），
真实 Runner 门禁全绿（一次极端慢 runner 上的 Raft RPC 超时经标准
失败 job 重跑恢复，未改代码）。

## 交付清单

1. **RPC 扩展**：`RpcMessageType` 增加 REPLICATION(34) /
   REPLICATION_RESPONSE(35) + 响应映射；
2. **ReplicationEventCodec**：ChangeEvent ↔ byte[]（长度前缀 +
   CRC32C，损坏/短包拒绝）；
3. **LwwConflictResolver**：高 timestamp 胜、同 timestamp 按源
   cluster id 字典序、同源 seq 幂等（重放安全）；
4. **CrossClusterSink**：目标端 LWW 决策 + StorageEngine 应用，
   被裁决事件不落盘；
5. **CrossClusterReplicationChannel**：复用 MultiRaftEndpoint RPC
   收发；
6. **CrossClusterWatermark**：按 (regionId, seq) 水位原子落盘，
   重启后跳过已应用事件（跨重启续传）；
7. **CrossClusterReplicaSink**：ReplicaSink 适配器，本集群
   ReplicationPipeline → 跨集群转发；
8. **混沌与验证**：分区窗口失败缓存 → 恢复重放幂等；E2E 单写一致 /
   双写 LWW 收敛 / 重复幂等 / FederationConsistencyVerifier 接线。

## 测试与门禁

- 新增测试 29 项（codec 5 + LWW 6 + sink 4 + watermark 5 + E2E 4 +
  收尾混沌/串联 5，surefire 口径）；
- 全量回归 14640 / 0 failures / 6 skipped；
- 真实 Runner：build / test / transaction-e2e × main/develop 全绿；
- 基准（phase60-multi-cluster-replication-report.md）：同步 ack
  复制吞吐 5,748 ops/s（本地基线）。

## 已知限制（如实记录）

- LWW 非 CRDT：并发双写丢弃低 timestamp 写（语义与
  FederationConsistencyVerifier 一致；CRDT 演进预留接口）；
- 复制为同步 ack 单事件路径，批量/异步流水线未做
  （与 ReplicationPipeline ASYNC 模式对齐列入后续）；
- 目标端水位落盘为显式 checkpoint（close/手动触发），周期刷盘未做；
- 跨集群复制未接 Raft 提案（复制事件直接应用，事务一致性由
  ChangeEvent 语义保证，跨集群 2PC 不在 M3 范围）。

## 后续

- v4.0 M4（生产收口）：ADR-0322 启动；
- M3 增强项（批量/异步复制、水位周期刷盘、CRDT 演进）按路线图排期。
