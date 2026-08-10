# Phase 13 评审报告：分布式优化

日期：2026-08-10 · 阶段：Phase 13 ✅

## 1. 架构变化

```text
RaftNode（批量/流水线复制）
  ├── RaftReplicationConfig（maxBatchEntries / maxBatchBytes /
  │   flushInterval / maxInflight）
  ├── 日志镜像缓存（持锁路径零文件读，消除心跳/选举停滞）
  └── ReplicationTracker（inflight / lastSentIndex）
SlotMigrationManager → MigrationCursor（lastKey/lastVersion/offset）
RpcServer/RpcClient → TLS + RpcAuthInterceptor + TokenBucket
MetadataClient → MetadataRaftGroup → 每副本 MetadataState
```

## 2. ADR 总结

| ADR | 决策 |
| --- | --- |
| ADR-0044 | 批量 AppendEntries + Pipeline + group commit（可配置 batch/bytes/flush/inflight） |
| ADR-0045 | MigrationCursor 单次扫描 + PAUSED + `slot-{start}.cursor` CRC 续传 |
| ADR-0046 | TLS（PEM）+ Token 认证（含过期）+ TokenBucket 限流 |
| ADR-0047 | 独立元数据 Raft 组，每副本独立状态机 |

## 3. 代码变化

- 批量/流水线复制：RaftNode 异步发送路径（batch collector + inflight
  上限 + flush 调度器），日志镜像缓存（logCache）使持锁路径零文件读；
- 游标迁移：SlotMigrationManager 单迭代器跨批次推进，pause/resume/
  recover，游标文件 CRC 保护；
- 安全 RPC：RpcServer/RpcClient 支持 TLS + 认证 + 限流；RpcClient
  连接与重试全程非阻塞（修复事件循环同步 connect 导致的提交停滞）；
- 元数据 Raft：MetadataCodec/State/Group/Client；修复"共享状态机多副本
  交错"缺陷（每副本独立状态机，读走 leader）；
- 部署文档：gateway/metadata/storage 角色、端口、YAML。

## 4. 测试统计

| 套件 | 数量 | 结果 |
| --- | --- | --- |
| RaftBatchReplicationTest | 15 | ✅ |
| MigrationCursorTest | 15 | ✅ |
| RpcSecurityTest | 19 | ✅ |
| MetadataRaftTest | 24 | ✅ |
| DistributedOptimizationIntegrationTest | 5 | ✅ |
| DistributedOptimizationBenchmarkTest | 4 | ✅ |
| Phase 13 新增合计 | 82 | ✅ |
| 全量回归（Phase 1–13） | 451 | ✅ 0 失败 |

## 5. 基准对比（phase13-report.md）

| 指标 | Phase 12 | Phase 13 | 目标 |
| --- | --- | --- | --- |
| 复制吞吐（TCP） | 700–1,359 ops/s | 22,169 ops/s | >5000 ✅ |
| 复制 P50/P99 | 0.65/2.16ms（单写者） | 2.50/7.80ms（64 写者） | 提供基线 |
| 迁移 MB/s | 16–20MB/s（100B） | 216–245MB/s（1KB） | >100MB/s ✅ |
| 迁移断点续传 | 349–549ms | 755–856ms | 提供基线 |
| RPC 安全开销 | — | +50–70%（142→229μs） | 提供基线 |
| 元数据故障转移 | — | 115–290ms | <5s ✅ |

## 6. 瓶颈分析

- 复制：单写者由空闲即刷保证低延迟（无回退），64 并发下批量/流水线
  充分生效（22K ops/s）；
- 迁移：单条 `MemTable.put` 固定成本主导小负载吞吐（100B ≈ 18MB/s、
  180K entries/s）；1KB 负载突破 244.8MB/s；
- RPC：TLS 握手/加解密带来 +50–70% 单调用开销；
- 元数据：空闲即刷后顺序写 69–104K ops/s，故障转移开销可忽略。

## 7. 已知限制

1. 小负载迁移受单条 put 成本限制 → MemTable 批量写接口（TD-030）；
2. 复制 P50≈2.5ms（64 写者）→ 自适应 flush / 异步客户端（TD-031）；
3. RPC 静态 token、无 mTLS → HMAC 签名轮换（TD-032）；
4. 元数据状态机为进程内内存态（Raft 日志未落盘到元数据组）；
5. 迁移仍为存量复制模型（增量/双写未实现）；
6. 基准为单机回环，未测跨机网络。

## 8. 下一阶段建议（Phase 14）

- MemTable 批量写接口（迁移小负载吞吐）；
- 自适应 flush 间隔 + 异步（非阻塞）propose 客户端；
- token 签名轮换 / mTLS；
- 元数据 Raft 组持久化日志；
- 跨机部署验证与故障注入。
