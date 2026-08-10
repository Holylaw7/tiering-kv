# ADR-0077: Raft Heartbeat Commit Bound

## Status

Accepted

## Context

Phase 19 全量回归验收时，`quorumLossBlocksCommitUntilFailover` 反复失败，
隔离可复现（2/3~4/6）。根因是真实共识缺陷：旧 leader 在分区期间追加了
未提交条目（term T1，index k）；新 leader（term T3）在 index k 提交了另一条
命令。空心跳（entries=[]，prevLogIndex=k-1，leaderCommit=k）到达旧 leader 时，
follower 用 `min(leaderCommit, lastLogIndex())` 推进 commitIndex，
把自己的冲突条目（term T1）错误提交并完成了悬挂的旧提案 future，
状态机也应用了与现任 leader 日志冲突的数据。

## Decision

1. Follower 侧（符合 Raft 论文 "index of last new entry" 规则）：
   - 空心跳：`verifiedIndex = request.prevLogIndex()`；
   - 非空请求：`verifiedIndex = 请求最后一条条目的 index`；
   - 仅当 `leaderCommit > commitIndex` 时推进
     `commitIndex = min(leaderCommit, verifiedIndex)`。
2. Leader 侧防御：对仍有未复制条目（`nextIndex <= lastLogIndex()`）的 peer
   跳过空心跳，避免 commitIndex 先于冲突数据到达；数据 flush 会携带条目与
   commitIndex。

## Alternatives

1. 仅 leader 侧跳过空心跳：缩小窗口但不能根除竞态，且依赖调度顺序。
2. follower 主动比对 leader 日志：需额外 RPC/元数据，复杂度高。
3. 保持现状：违反 Raft 提交安全，未提交条目可“虚假成功”，禁止。

## Consequences

优点：

- 旧提案只会显式失败，或由旧 leader 合法重夺领导权后的新条目真实提交；
- 已提交条目仍可通过心跳快速传播（已追平 peer 不受影响）；
- 与 Raft 论文语义对齐，消除脏提交与状态机分叉。

缺点：

- 滞后 peer 的 commitIndex 传播延迟到数据 flush 到达（最多一个 flush 周期）。

风险：

- 低；已有 1339 项测试全量回归覆盖（含确定性回归测试）。

## Implementation

代码影响范围：

- `src/main/java/io/tieringkv/cluster/raft/RaftNode.java`
  - `receive(AppendEntriesRequest)`：空心跳 commit 上界改为 prevLogIndex；
  - `buildHeartbeatCallsLocked()`：有未复制条目时跳过空心跳。
- `src/test/java/io/tieringkv/cluster/RaftTest.java`
  - 新增 `emptyHeartbeatMustNotCommitConflictingEntry` 确定性回归。
- `src/test/java/io/tieringkv/cluster/ChaosValidationTest.java`
  - `quorumLossBlocksCommitUntilFailover`：成功分支必须最终收敛（探针驱动）。
- `src/test/java/io/tieringkv/cluster/RegionChaosTest.java`
  - `transferUnder200msLatencyAndLoss`：单次合法失败重试，不变量为最终成功。
