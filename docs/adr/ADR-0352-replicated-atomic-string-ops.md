# ADR-0352: Replicated AtomicStringOps via Raft ATOMIC Commands (TD-081 Closure)

## Status

Accepted

## Context

TD-081（ADR-0351 登记）：`ReplicatedStorageEngine` 只实现
`StorageEngine`，Redis Cluster 网关数据面（`RedisClusterGateway`
经 `RaftGroupManager.storageFor` 拿到复制存储）在 TTL / INCR /
APPEND / GETSET / GETDEL / SETNX / EXPIRE / PERSIST 上
`instanceof AtomicStringOps` 失败，命令层静默回退到 get+put：

- `TTL/PTTL` 恒为 -1 / -2（TTL 已复制到本地 MemTable，但读不到）；
- `INCR/DECR/INCRBY/APPEND/GETSET/GETDEL/SETNX` 退化为非原子实现，
  并发下存在丢失更新；
- `EXPIRE/PERSIST` 功能可用但不原子。

修复约束：

1. 不修改 `RaftNode` / Raft 协议：Raft 日志载荷（
   `LogEntryCodec` COMMAND_TYPE=OPAQUE）由 `ReplicatedStorageEngine`
   自行承载，命令格式扩展对 Raft 层透明；
2. 必须确定性重放：ATOMIC 命令进入 Raft 日志后，任一副本启动重放
   或快照恢复后，都必须得到与 Leader 相同的最终状态；
3. 必须回传结果：`INCR` 的新值、`GETSET` 的旧值等需要从状态机
   apply 阶段取回给调用方；
4. 网关当前只路由字符串命令（`update(UnaryOperator)` 语义的
   Hash/List/Geo/Json/Bit 未接入网关），但接口必须可扩展。

## Decision

**在 ReplicatedStorageEngine 命令帧内新增 ATOMIC 命令类型，并把
原子操作放入 Raft 状态机 apply 阶段执行**：

1. 命令帧扩展（向后兼容）：
   - PUT = 类型字节 1（布局不变，旧日志可解）；
   - DELETE = 类型字节 2（布局不变）；
   - ATOMIC = 类型字节 3 + op 码（1B）+ key + value +
     ttl/expireAtMillis（8B）+ delta（8B）；
2. `applyLocal(index, command)` 对 ATOMIC 执行确定性操作
   （INCREMENT / APPEND / GET_SET / GET_SET_PRESERVING_TTL /
   GET_DELETE / PUT_IF_ABSENT / PERSIST / EXPIRE_AT），并把结果按
   log index 存入结果表；Leader 的 `raft.propose` future 在 apply
   之后完成（`applyCommittedLocked` 先 accept 后 complete），
   `thenApply(index -> takeResult(index))` 即可取回结果；
3. 结果表使用带时间戳的条目，取走后删除；超时未取走的条目按
   60s 保留期惰性清理，防止长期泄漏；
4. 只读方法本地执行：`ttlMillis` / `versionOf` 委托本地引擎；
5. `update(key, transform)` 无法序列化 lambda，采用
   **Leader 本地 RMW + 复制结果**：Leader 在本地状态上执行
   transform（保留 TTL），把最终值作为 PUT（或 null 则 DELETE）
   经 Raft 复制。并发同键 RMW 的丢失更新风险与既有 get+put 回退
   一致，且网关当前不路由此类命令；写入的确定性收敛由复制 PUT
   保证；
6. 底层 `local` 不支持 `AtomicStringOps` 时原子写显式抛出
   `UnsupportedOperationException`，禁止静默回退。

## Alternatives

1. 在 CommandEngine 层为复制引擎包一层命令级适配器：仍无法让
   `instanceof AtomicStringOps` 通过，且网关每处都要特判；
2. 只实现只读 `ttlMillis` + 写命令继续 get+put：INCR/APPEND/
   GETSET 的原子性缺陷保留，TD 只修一半；
3. 为每个复合类型命令新增序列化 op（HSET/LPUSH/...）：网关未路由
   这些命令，当前收益低、接口面大，留待网关扩展时按需增加；
4. 全量无锁/异步复制：超出本 TD 范围（TD-015 已评估维持 RWLock）。

## Consequences

优点：

- 网关字符串命令获得与单机一致的原子语义与 TTL 可读性；
- ATOMIC 命令经 Raft 日志确定性重放，Leader 切换 / 重启恢复后
  副本状态一致；
- 结果回传复用现有 propose future，无协议改动，Raft 层零侵入；
- 旧日志（PUT/DELETE 帧）向后兼容。

缺点：

- `update(UnaryOperator)` 仍是 Leader 本地 RMW（非锁步确定性），
  文档明确语义边界；
- 结果表在超时场景有 60s 保留期，极端高并发下存在短暂内存占用。

风险：

- 低。ATOMIC apply 仅调用 MemTable 既有原子方法；失败（异常）时
  RaftNode 记录状态推进但异常会向调用方传播（与 PUT 异常路径一致）。

## Implementation

- `src/main/java/io/tieringkv/cluster/ReplicatedStorageEngine.java`：
  CommandType.ATOMIC + AtomicOp 枚举 + 帧编解码扩展 + applyLocal
  分发 + 结果回传 + AtomicStringOps 实现；
- 新增 `src/test/java/io/tieringkv/cluster/
  ReplicatedAtomicOpsTest.java`（复制一致性 / 网关端到端 /
  Leader 切换 / 日志重放 / 不支持引擎显式失败）；
- 本 ADR；CHANGELOG；ROADMAP TD-081 关闭。
