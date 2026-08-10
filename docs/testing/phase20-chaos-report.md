# Phase 20 混沌报告：事务崩溃/分区/恢复验证

Phase 20 · 2026-08-10

## 1. 目标与执行方式

目标（ADR-0082）：3 节点 Linux + Docker + tc netem（delay 100ms /
loss 5% / partition / kill -9 / disk slow）验证 MVCC 事务一致性、
2PC 恢复、leader transfer、migration。

执行结果：

- Docker CLI 29.6.1 + WSL Ubuntu 可用，daemon 已启动；
- `deploy/docker-compose.cluster.yml` 构建上下文已修正（仓库根），
  并为三节点增加 NET_ADMIN（tc netem 前置条件）；
- 容器内镜像构建在 `mvn dependency:go-offline` 失败
  （Maven Central 网络受限），**真实跨机混沌未执行**；
- 按工程规则不伪造结果，登记 TD-040/TD-043。

## 2. 本地等价混沌（进程内）

`Phase20TransactionChaosTest`（30 项）以进程内故障注入覆盖：

| 故障 | 覆盖场景 |
| --- | --- |
| kill（崩溃点注入） | prewrite 后、COMMIT 落盘后、apply 后、回滚后 |
| 分区 | commit 前回滚全部、COMMIT 决策后补完 |
| 重启 | 日志重开重放、leader 重启无丢失提交 |
| 乱序/并发 | 并发事务无永久锁、GC 与恢复并发 |
| 损坏 | 日志尾部截断容忍、中部 CRC 损坏抛错 |
| 随机 | 15 轮随机崩溃点：无幻影/无丢失/无永久锁 |

不变量：

- 已提交事务永不丢失（COMMIT 落盘 → 重放补完）；
- 未提交事务不虚假成功（仅 PREWRITE → 无提交）；
- 失败事务不留下永久锁（超时恢复 + 回滚日志）。

## 3. 复现命令

```bash
mvn -Dtest=Phase20TransactionChaosTest,TxnLeaderCrashTest,TxnReplayTest test
```

## 4. 结论与遗留

- 本地等价混沌全部通过，事务恢复语义满足无幻影/无丢失/无永久锁；
- 全量回归期间发现 ChaosValidationTest 在 100ms 延迟下无限挂起：
  根因是 leader 被杀时未决 Raft 提案 future 永不完成 + `put()` 无超时
  join。修复为 `ReplicatedStorageEngine.putAsync` 5s 有界等待
  （ADR-0050 语义落地），并新增 `pendingProposalFailsOnLeaderKillWithinTimeout`
  回归测试；混战用例改用 250/200 选举超时避免 100ms 延迟下的领导权抖动。
- 真实跨机 tc netem 验证因环境网络受限未执行（TD-040）；
- 事务网关尚未接入 Multi-Raft/Region 网络路径（TD-043），
  跨机事务验证计划在 Phase 21 完成。
