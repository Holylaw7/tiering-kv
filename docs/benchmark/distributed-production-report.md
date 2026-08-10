# 分布式生产化基准报告（Distributed Production Report）

Phase 12 · 2026-08-10

环境：本机 Windows 11，JDK 21.0.12（编译目标 17），20 核，`-Xmx1g`；
网络为 127.0.0.1 TCP 回环，日志为文件 RaftLog（ASYNC 默认），
状态文件常驻通道写入（term/votedFor 变更 force，commitIndex 缓冲）。

## RaftLog 追加（ASYNC）

| 指标 | 值 |
| --- | --- |
| 吞吐（100K 条） | 102,165 ops/s |
| P50 / P95 / P99 | 6 / 20 / 27 μs |

说明：ASYNC 模式为缓冲写入 + 100ms 周期 force，存在 ≤100ms 丢失窗口
（ADR-0039）；SYNC 模式逐条 force，吞吐显著更低但提供强持久化——
本基准只测量 ASYNC，与 Phase 4 WAL 口径一致。

## 3 节点 TCP 集群提交（真实网络 + 持久化路径）

写路径：leader → RaftLog append → 2 个 follower 同步 RPC → 多数派 →
commit → apply → 立即补发 commitIndex（CommitNotifier）。

| 指标 | Phase 11（进程内） | Phase 12（TCP + 文件日志） |
| --- | --- | --- |
| 复制写吞吐 | 145–154K ops/s | 1,359 ops/s |
| 写 P50 | 0.004–0.006ms | 0.646ms |
| 写 P95 | 0.013–0.018ms | 1.239ms |
| 写 P99 | 0.027–0.058ms | 2.161ms |
| 复制滞后 | 13–35ms（心跳周期约束） | **0ms**（P50/P99/Max，立即补发） |

结论：

- 复制滞后从 13–35ms 降至 **<1ms**，达成 Task 4 目标（<5ms ✅）；
- 吞吐下降 ~100 倍是"真实 TCP 往返 ×2 + 日志/状态写入"的合理代价；
  当前为同步串行 propose，后续可做批量/并行复制（已登记）；
- 延迟基线：单次 RPC 回环 P50≈100μs，两次串行 ≈0.2ms，其余为日志与
  状态写入。

## RPC（Netty TCP，单连接复用）

| 指标 | 值 |
| --- | --- |
| 顺序调用吞吐（20K） | 9,254 ops/s |
| P50 / P95 / P99 | 100 / 185 / 252 μs |
| 连接数 | 1（复用） |

支持：请求关联（RequestId）、超时（3s）、幂等重试（2 次）、断线重连、
三类 Raft 消息（AppendEntries / RequestVote / InstallSnapshot）。

## Slot 迁移

| 指标 | 值 |
| --- | --- |
| 迁移吞吐（100K 条 × 100B ≈ 9.5MB） | 16.1 MB/s |
| 断点续传恢复（90K 剩余条目） | 549 ms |

说明：单线程复制 + 每批重建源快照迭代（MemTable 快照成本占主导）；
批量 50K 时吞吐 16–17MB/s。生产可改为单次迭代 + 游标 checkpoint
（已登记后续优化）。

## 对比结论

| 能力 | Phase 11 | Phase 12 |
| --- | --- | --- |
| Raft 日志 | 内存 | 文件分段 + CRC + 尾部截断恢复 |
| 持久状态 | 无 | term/votedFor/commitIndex 落盘 |
| 传输 | 对象直调 | Netty TCP（复用/超时/重试/关联） |
| 快照 | 无 | SnapshotManager + InstallSnapshot |
| 复制滞后 | 13–35ms | <1ms ✅ |
| 选举 | 124–310ms | 进程内同模型（TCP 下 <5s 保持） |
| 迁移 | 静态分片 | 在线迁移 + checkpoint 续传 |
