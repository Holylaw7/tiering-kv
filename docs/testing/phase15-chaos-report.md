# Phase 15 混沌验证报告（Chaos Validation）

Phase 15 · 2026-08-10

## 1. 目标

TD-035：从“进程内故障模拟”升级到生产级混沌验证，覆盖网络延迟、丢包、
分区、磁盘慢、leader 击杀，验证：

- 无数据丢失（已提交数据在任何故障下保留）；
- leader 选举与故障转移；
- replica catch-up（恢复后追平）；
- 未提交提案绝不虚假成功。

## 2. 环境与工具约束（如实说明）

开发环境为 Windows，`tc netem` 不可用，且无法在单机工作区搭建三台独立
JVM 的真实跨机网络。因此：

- 故障注入在 `RaftTransport` 层实现（`ChaosValidationTest`），语义对齐
  tc netem：延迟、丢包、分区（含链路级非对称分区）；
- 真实 TCP + 独立 Netty 传输的集群路径已由 Phase 11/14 的
  `TcpClusterIntegrationTest` 覆盖（真实 socket、独立传输、持久日志）；
- 本阶段新增 16 项混沌测试 + 1 项 Raft 回归测试，全部通过（3 轮稳定）；
- 真实跨机 `tc netem` 部署验证保留为后续阶段（见第 8 节）。

## 3. 测试矩阵（16 项）

| # | 场景 | 注入 | 验证 |
| --- | --- | --- | --- |
| 1 | 100ms 全链路延迟 | 延迟 | 提交成功，副本收敛 |
| 2 | 5% 丢包 | 丢包 | 30 条写入无丢失 |
| 3 | 10% 丢包 | 丢包 | 20 条写入重试后收敛 |
| 4 | follower 分区 | 链路分区 | 多数派继续写，恢复后追平 |
| 5 | leader 分区 | 节点分区 | 新 leader 选出，旧 leader 恢复后降级 |
| 6 | follower 磁盘慢 | append 延迟 5ms | 提交不被阻塞，慢副本追平 |
| 7 | leader 磁盘慢 | append 延迟 2ms | 多数派提交成功 |
| 8 | leader 击杀 | 节点 kill | 已提交 10 条数据全部保留 |
| 9 | replica 重启 | kill + 持久日志重启 | 重启后追平 20 条 |
| 10 | 单 follower 击杀 | 节点 kill | 双节点多数派持续提交 |
| 11 | 少数派重启 | kill + 重启 | 重启后恢复全部数据 |
| 12 | 混合故障序列 | 延迟→丢包→分区→击杀 | 20 条写入无丢失 |
| 13 | 滞后 replica 恢复 | 分区累积滞后 | 追平 20 条 |
| 14 | 并发写入 + leader 击杀 | 4 写者 × 25 异步写 | 已确认写入全部保留 |
| 15 | 磁盘慢 + leader 击杀 | 组合 | 新 leader 恢复数据，旧节点重启追平 |
| 16 | 法定人数丢失 | 链路级分区（follower 可互达） | 提案悬挂→新 leader 提交→旧提案显式失败 |

## 4. 发现并修复的真实缺陷

测试 16 暴露了一个 Raft 实现缺陷：

**问题**：旧 leader 的未提交提案（future 按 index 存于 `pendingCommits`）
在被新 leader 的冲突条目截断后，同 index 的新条目提交时会复用并完成旧
future，导致“从未提交的提案”被客户端视为成功提交——数据一致性风险。

**修复**（`RaftNode.receive(AppendEntriesRequest)`）：
冲突截断时调用 `failPendingFromLocked(fromIndex)`，将 index >= 截断点的
全部 pending future 显式失败（`IllegalStateException("entry superseded")`）。

**回归保护**：

- `RaftTest.truncatedPendingProposalFailsInsteadOfCompleting`（悬挂提案 →
  冲突截断 → 显式失败）；
- `ChaosValidationTest.quorumLossBlocksCommitUntilFailover`（法定人数丢失
  场景下旧提案绝不虚假成功）。

## 5. 结果（2026-08-10，3 轮运行）

16/16 混沌测试 + 1/1 Raft 回归测试，3 轮全部通过。

| 验证点 | 结果 |
| --- | --- |
| 无数据丢失 | ✅ 所有已提交写入在击杀/分区/重启后完整保留 |
| leader 选举 | ✅ 8s 内完成故障转移（常规场景 <1s） |
| replica catch-up | ✅ 分区/慢盘/重启副本在 10s 内追平 |
| 已提交 vs 未提交区分 | ✅ 已提交保留；未提交显式失败，绝不虚假成功 |
| 分区下可用性 | ✅ 多数派存活可继续提交；法定人数丢失时提交阻塞 |

## 6. 观察到的边界行为（记录，不隐藏）

1. **日志不一致副本的选举竞争**：若 leader 击杀前副本日志长度不一致，
   选举可能出现 term 膨胀（极端观察 term=56）。属于 Raft 标准行为：
   日志落后的节点无法赢得选举；最完整的日志最终会胜出。测试侧要求
   kill 前 `awaitAllSee`（生产运维亦应在故障转移前确认多数派收敛）。
2. **100ms 单向延迟 + 默认选举超时（100-180ms）** 会触发反复竞选；
   该场景需将选举超时配置为大于“首条心跳到达时间”。这是配置问题，
   不是协议缺陷。
3. 混沌测试在 `RaftTransport` 层注入故障，不含真实网卡丢包/队列抖动，
   延迟数字不代表真实网络行为。

## 7. 实现文件

- `src/test/java/io/tieringkv/cluster/ChaosValidationTest.java`（16 项）；
- `src/test/java/io/tieringkv/cluster/RaftTest.java`（+1 项回归）；
- `src/main/java/io/tieringkv/cluster/raft/RaftNode.java`（缺陷修复）；
- `src/main/java/io/tieringkv/cluster/ClusterNode.java`（`putAsync` 暴露）。

## 8. 限制与下一阶段

- 未执行真实跨机 `tc netem`、独立 JVM 部署（受单机 Windows 环境限制）；
- 未注入磁盘满、IO 错误、随机崩溃（Chaos Monkey 风格）；
- 下一阶段：容器化三节点部署（Docker Compose）+ `tc` 网络损坏注入 +
  随机混沌调度（kill -9 / 磁盘满 / 时钟跳变）。
