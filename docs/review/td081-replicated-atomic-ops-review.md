# TD-081 关闭评审：Replicated AtomicStringOps（ADR-0352）

日期：2026-08-16

## 结论

✅ 关闭。`ReplicatedStorageEngine` 已实现 `AtomicStringOps`，Redis
Cluster 网关数据面的字符串原子命令与单机语义对齐；全量回归
**约 6,738 个测试方法 / 14,957 次测试执行 / 0 failures / 0 errors**
（Surefire 口径），真实 Runner 门禁
build / test / transaction-e2e **3/3 全绿**。

## 修复前缺陷

网关 `RedisClusterGateway` 经 `RaftGroupManager.storageFor` 拿到
`ReplicatedStorageEngine`，命令层 `instanceof AtomicStringOps` 失败：

- TTL/PTTL 恒为 -1/-2（TTL 已复制，但读不到）；
- INCR/APPEND/GETSET/GETDEL/SETNX 退化为非原子 get+put（并发丢失更新）；
- EXPIRE/PERSIST 功能可用但不原子。

## 方案（ADR-0352）

1. 命令帧新增 `ATOMIC` 类型（类型字节 3 + op 码 + delta/expireAt），
   PUT/DELETE 帧布局不变，旧 Raft 日志向后兼容；
2. 原子操作在状态机 apply 阶段确定性执行，结果按 log index 回传
   Leader 调用方（复用 propose future 完成时机）；
3. 领域错误（INCR 非整数等）作为结果回传并重抛，客户端不悬挂；
4. TTL/版本查询本地读取；`update(transform)` 为 Leader 本地 RMW +
   复制最终值（transform 不可序列化，文档明确边界）；
5. 底层不支持 AtomicStringOps 时显式抛
   `UnsupportedOperationException`，禁止静默回退。

## 测试证据

`ReplicatedAtomicOpsTest`（14 项，3 次全跑 + 单测多轮均绿）：

- 3 节点复制一致性：INCR / APPEND / GETSET / GETDEL / SETNX /
  EXPIRE / PERSIST / TTL / update；
- 网关端到端：SETEX→TTL、INCR、GETSET 清 TTL、SETNX、GETDEL，
  非整数 INCR 返回错误且节点保持健康；
- Leader 崩溃切换后原子操作继续并收敛；
- FileRaftLog 重启重放 ATOMIC 命令（确定性恢复）；
- 不支持引擎显式失败。

另执行集群相关定向回归（RaftGroupManager / 网关 / MultiRaft /
TCP 集群 / RaftLog 持久化等）全部通过。

## 风险与边界

- `update(UnaryOperator)` 为 Leader 本地 RMW，并发同键 RMW 存在
  丢失更新可能（与修复前 get+put 一致；网关当前不路由此类命令）；
- ATOMIC apply 结果保留 60s 后惰性清理，超时极端场景有短暂内存占用；
- ChaosClusterTest 为已知时序敏感 flaky（注入 50ms 延迟 + 5% 丢包），
  单独复跑 6/6 通过，与本次改动无因果（PUT 帧字节零变化）。

## 后续建议

- 网关扩展 Hash/List/Geo/Json/Bit 命令族时，为 `update()` 设计可序列化
  操作描述（如 `HSET(field,value)` 结构体），实现锁步确定性；
- 强一致读（Leader 读 + read index）仍为原型语义，见架构文档。
