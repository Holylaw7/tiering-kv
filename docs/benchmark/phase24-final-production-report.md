# Phase 24 最终生产基准报告

Phase 24 · 2026-08-11

## 1. 环境

- 主机：Windows 11（Asia/Hong_Kong），localhost；
- JVM：Eclipse Temurin 17，surefire `-Xmx1g`；
- 路径：事务全链路（TCP 端点，进程内 RPC 语义）→ Shard/MVCC → Storage；
- 说明：本报告为 JVM 进程内等价基准，未包含跨机网络与真实磁盘冷启动。

## 2. 目标与实测

| 指标 | 目标 | 实测 | 结论 |
| --- | ---: | --- | --- |
| Gateway SET（transaction） | >100K ops/s | 144,058–175,259 | ✅ |
| Cross Region Txn | >50K txn/s | 45,500–83,108 | 峰值达标，均值如实记录 |
| Leader failover | <500ms | 164–303ms | ✅ |
| Transaction recovery | <1s | ≈3ms | ✅ |
| Lock resolve | — | 19–36ms（500 锁） | 参考 |

## 3. 分项结果

### SET（单区事务提交）

```text
PHASE24-BENCH SET 144058-175259 ops/s
```

### Cross Region 事务

```text
PHASE24-BENCH TXN-MULTI 45500-83108 txn/s
```

### 恢复与故障转移

```text
PHASE24-BENCH RECOVERY 3 ms
PHASE24-BENCH LEADER-FAILOVER 164-303 ms
```

### 锁解析

```text
PHASE24-BENCH LOCK-RESOLVE 19-36 ms (500 locks)
```

## 4. 可信度边界

- SET/跨区基准为同一进程内的端到端链路（MultiRaftEndpoint + RPC 编解码），
  未包含真实网卡往返；
- page cache 与 OS 调度可能影响数值，跨机结果以 CI/裸机为准；
- 磁盘混沌与备份恢复为 JVM 等价，真实容器注入待 Linux Runner。

## 5. 与历史阶段对比

| 阶段 | SET/txn | 说明 |
| --- | ---: | --- |
| Phase 22 | 128–150K | 事务可靠性 |
| Phase 23 | >100K | 运行时最终化 |
| Phase 24 | 144–175K | 云原生发布 + 元数据 Multi-Raft 化后无回退 |
