# Phase 15 生产验证基准报告

Phase 15 · 2026-08-10

## 1. 方法说明（如实声明）

全部基准为进程内原型（in-process）：

- 迁移：MemTable → StreamingMigrator → MemTable，含游标持久化（真实文件 IO）；
- Raft：3 节点进程内 LocalRaftTransport，不含 TCP/网络与 WAL fsync；
- 吞吐：开环测量（写者满速提交，测最大处理率）；
- 延迟：闭环测量（每个写者等待回调后再发下一请求，测可持续并发下的
  端到端延迟）；
- 运行环境：Windows 本机、-Xmx1g、surefire 内运行。

因此数值不代表真实跨机网络/磁盘部署的绝对能力，仅用于阶段间对比与
瓶颈定位。

## 2. 流式迁移吞吐（TD-030）

| 条目大小 | Phase 14（snapshot 迁移） | Phase 15（流式迁移） | 目标 | 结果 |
| --- | --- | --- | --- | --- |
| 100B | 18~20 MB/s | 59.8 MB/s | >100 MB/s | ❌ 未达 |
| 1KB | — | 173.3 MB/s | >300 MB/s | ❌ 未达 |
| 10KB | — | 589.8 MB/s | — | ✅ 优秀 |

改进：100B 提升约 20 倍（2.9→59.8，修复每批重建 O(N) 快照迭代器）；
1KB 提升约 12.5 倍（13.8→173.3）。

未达标瓶颈分析（不隐藏）：

1. 写路径每条目发生 3 次数组拷贝：`Mutation` 构造克隆、
   `Mutation.key()/value()` 访问器克隆、`KeyValueEntry` 构造克隆；
2. `applyBatch` 按段加锁 + SkipList 插入 + 内存计量，小条目每条约 1.7µs；
3. 100B 目标隐含 100 万条目/s 的处理率，需要零拷贝批量写路径
   （下一阶段：`MemTable.applyRaw` 或所有权转移式 Mutation）。

## 3. 全异步 Raft 提案（TD-031）

### 3.1 吞吐（开环）

| 写者数 | Phase 14（同步等待） | Phase 15（异步批量） | 目标 | 结果 |
| --- | --- | --- | --- | --- |
| 1 | 37~68 K ops/s | 129 K ops/s | >100 K | ✅ |
| 64 | — | 259 K ops/s | >200 K | ✅ |
| 256 | — | 331 K ops/s | 报告 | ✅ |

### 3.2 延迟（闭环）

| 写者数 | Phase 15 P50 | Phase 15 P95 | Phase 15 P99 | 目标 | 结果 |
| --- | --- | --- | --- | --- | --- |
| 1 | 0.004 ms | 0.005 ms | 0.009 ms | <10 ms | ✅ |
| 64 | 0.039 ms | 2.168 ms | 3.071 ms | <10 ms | ✅ |
| 256 | 2.125 ms | 7.698 ms | 9.824 ms | 报告 | ✅（临界） |

实现要点：`AsyncReplicationClient` 提交线程内联批量 drain +
`RaftNode.proposeBatch`（N 请求一次 AppendEntries）；队列 CRITICAL 背压；
leader 变更整批重试 ≤3；冲突截断提案显式失败。

## 4. 混沌恢复时间

3 轮 leader 击杀 → 新 leader 选举 → 探针写 → 副本收敛：

| 指标 | Phase 14 | Phase 15 | 目标 |
| --- | --- | --- | --- |
| 选举 p50 | 124~310 ms | 155 ms | <5 s ✅ |
| 选举 max | — | 173 ms | <5 s ✅ |
| 探针写 p50 | — | ~0 ms | — |
| 副本收敛 p50 | — | ~0 ms | — |

## 5. TLS 证书轮换延迟

40 轮 `CertificateManager.rotate()`（SslContext 重建 + 原子切换，不含
证书签发）：

| 指标 | 数值 |
| --- | --- |
| min | 9.3 ms |
| p50 | 13.5 ms |
| p99 | 27.6 ms |
| max | 27.6 ms |

轮换为低频运维操作，无硬性目标；原子切换保证已有连接不中断。

## 6. 目标达成汇总

| 指标 | 目标 | 实际 | 状态 |
| --- | --- | --- | --- |
| 迁移 100B | >100 MB/s | 59.8 MB/s | ❌ 未达（写路径 3 次拷贝） |
| 迁移 1KB | >300 MB/s | 173.3 MB/s | ❌ 未达（同上） |
| 迁移 10KB | — | 589.8 MB/s | ✅ |
| Raft 1 写者 | >100 K ops/s | 129 K | ✅ |
| Raft 64 写者 | >200 K ops/s | 259 K | ✅ |
| Raft P99（1/64 写者） | <10 ms | 0.009 / 3.071 ms | ✅ |
| 混沌恢复 | <5 s | 155 ms | ✅ |
| TLS 轮换 | 无硬目标 | p50 13.5 ms | 报告 |

## 7. 实现与复现

- `src/test/java/io/tieringkv/benchmark/cluster/Phase15ProductionValidationBenchmarkTest.java`
- 运行：`mvn -Dtest=Phase15ProductionValidationBenchmarkTest test`
- 输出前缀：`PHASE15-BENCH`

## 8. 下一阶段建议

1. 零拷贝批量写（所有权转移 Mutation / `applyRaw`），目标 100B 迁移
   >100 MB/s；
2. 真实 TCP + 独立 JVM + `tc netem` 跨机基准；
3. WAL fsync 策略下的复制写吞吐（ALWAYS/EVERY_SEC）对比。
