# Phase 14 评审报告：生产加固

日期：2026-08-10 · 阶段：Phase 14 ✅

## 1. 架构变化

```text
MemTable.applyBatch（Mutation/BatchWriteRequest，单段单锁 + 版本预分配）
WALWriter.appendBatch（N 条记录一次段追加）
AdaptiveFlushController（动态间隔 50–500ms + 动态水位）
ReplicationController（动态 batch 16–512 / flush 1–10ms）+ putAsync
HmacToken/HmacAuthInterceptor（HMAC-SHA256 + nonce 防重放 + 双密钥轮换）
RpcTlsConfig（ONE_WAY / MUTUAL mTLS）
MetadataRaftGroup.createPersistent（FileRaftLog + MetadataSnapshot + 恢复）
```

## 2. ADR 决策

| ADR | 决策 |
| --- | --- |
| ADR-0048 | 批量变更（单段单锁、版本按请求序预分配、WAL 批量追加） |
| ADR-0049 | 自适应 Flush（内存/写速率/延迟/SSTable 因子 + EMA + 限幅） |
| ADR-0050 | 自适应复制（pending/RTT/lag → batch/flush）+ 异步提案 |
| ADR-0051 | HMAC-SHA256 + nonce 防重放 + 密钥轮换 + mTLS |
| ADR-0052 | 元数据 FileRaftLog + MetadataSnapshot + 恢复 |

## 3. 实现摘要

- 批量写：MemTable.applyBatch（预校验 → 版本预分配 → 按段单锁应用），
  WAL BatchWriter（一次段追加），迁移复制改用 512 条批量；
- 自适应 Flush：AdaptiveFlushController + FlushScheduler 自动巡检；
- 自适应复制：ReplicationController 接入 RaftNode（动态 batch/flush、
  RTT 采样），ReplicatedStorageEngine.putAsync（超时/取消/重试）；
- 安全：HmacToken/HmacAuthInterceptor/NonceCache/RpcTlsConfig；
- 元数据持久化：MetadataStateCodec + createPersistent（重启拓扑保留）。

## 4. 测试统计

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| MemTableBatchWriteTest | 21 | ✅ |
| AdaptiveFlushControllerTest | 16 | ✅ |
| ReplicationControllerTest | 19 | ✅ |
| HmacSecurityTest | 22 | ✅ |
| MetadataPersistenceTest | 14 | ✅ |
| FailureInjectionTest | 5 | ✅ |
| ProductionHardeningBenchmarkTest | 4 | ✅ |
| Phase 14 新增合计 | 101 | ✅ |
| 全量回归（Phase 1–14） | 待最终统计 | 见下方 |

## 5. 基准对比（phase14-production-report.md）

| 指标 | Phase 13 | Phase 14 | 目标 |
| --- | --- | --- | --- |
| 100B 迁移 | 17.7MB/s | 18.3MB/s | >100MB/s ❌ |
| Raft 吞吐 | 22K ops/s | 37.3K ops/s | >50K ❌ |
| HMAC 开销 | — | ≈0% | 提供基线 ✅ |
| 元数据重启 | — | 194ms | 提供基线 ✅ |

## 6. 故障注入结果

5/5 通过：延迟/断连/丢包/杀 leader/日志损坏，系统无数据丢失并自动恢复。

## 7. 剩余限制

1. 100B 迁移瓶颈在源迭代器快照归并（非 put）→ 流式迭代器/批量快照
   （TD-030 延伸）；
2. 复制 37K ops/s（同步等待写者）→ 异步回调客户端（TD-031 延伸）；
3. 元数据状态恢复依赖 FileRaftLog SYNC，高写入下成本待测；
4. mTLS 证书生命周期管理（轮换流程未自动化）；
5. 故障注入为进程内模拟，未覆盖真实网络工具（tc netem）。

## 8. Phase 15 建议

- 流式迭代器 + 批量快照（打通 100B 迁移吞吐）；
- 全异步回调式复制客户端（>50K ops/s）；
- 证书自动化轮换与混沌测试流水线；
- 跨机真实部署验证与容量压测。
