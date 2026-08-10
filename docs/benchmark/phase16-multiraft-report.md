# Phase 16 Multi-Raft 基准报告

Phase 16 · 2026-08-10

## 1. 方法说明（如实声明）

- 迁移/组吞吐：进程内（MemTable + LocalRaftTransport），含游标持久化；
  组吞吐为**每组独立线程并发写入**（真实并行，非串行伪扩展）；
- 跨机延迟代理：真实 TCP 回环 + 单端口 MultiRaftEndpoint（无 netem，
  本机无 Docker 守护进程）；容器跨机口径见
  [phase16-cross-machine.md](../deployment/phase16-cross-machine.md)；
- 吞吐为开环（满速提交），延迟为同步 put 往返（leader 提交后返回）。

## 2. Phase 15 vs Phase 16：零拷贝迁移（TD-033）

| 条目大小 | Phase 15（拷贝路径） | Phase 16（零拷贝） | 目标 | 状态 |
| --- | --- | --- | --- | --- |
| 100B | 59.8 MB/s | 60.8~82.7 MB/s | >100 MB/s | ❌ 未达（+2~38%） |
| 1KB | 173.3 MB/s | 167.6~223.1 MB/s | >300 MB/s | ❌ 未达 |
| 10KB | 589.8 MB/s | 600.0~631.0 MB/s | — | ✅ |

未达标分析（不隐藏）：写路径数组拷贝 3 次 → 0 次，但 100B 目标隐含
100 万条目/s，当前约 75 万条目/s；剩余瓶颈为每条目固定开销
（迭代器归并 ~0.22s / 迁移循环 ~0.17s / 分批写入 ~0.15s，500K 条目），
并行迁移或按段多线程写入为下一阶段方向。

## 3. Zero-Copy 批写

`applyRawBatch` 平面桶分组（无装箱）+ `SkipList.putAndGetOld` 单次查找 +
`KeyValueEntry.liveOwned` 所有权转移；`RawBatchWriteTest` 20 项覆盖
版本顺序 / TTL / 并发 / 内存计量 / 所有权契约。

## 4. Multi-Raft 吞吐（线性扩展趋势）

| 组数 | 吞吐 | 相对 1 组 |
| --- | --- | --- |
| 1 | 92~110 K ops/s | 1.0× |
| 2 | 222~314 K ops/s | 2.0~3.4× |
| 4 | 404~841 K ops/s | 3.7~9.2× |

结论：组间天然并行，吞吐随组数**超线性扩展**（并行写入使 proposeBatch
批量效率提升；目标"线性扩展趋势"✅）。

## 5. 跨机延迟（TCP 单端口多组，回环代理）

| 指标 | 数值 |
| --- | --- |
| p50 | 0.184 ms |
| p95 | 0.337 ms |
| p99 | 0.551 ms |

共享单端口 MultiRaftEndpoint 组前缀路由，多组复用连接池；
真实跨机 netem 延迟/丢包待 Docker+Linux 环境执行（部署产物已提供）。

## 6. 故障恢复

3 轮 leader 击杀 → 新 leader + 探针写：

| 指标 | 数值 | 目标 |
| --- | --- | --- |
| min | 154 ms | — |
| p50 | 183 ms | — |
| max | 191 ms | <5 s ✅ |

## 7. 混沌发现并修复的缺陷

新 leader 以非空日志当选后，nextIndex 初始化为 lastLogIndex+1，数据
flush 跳过滞后 follower；心跳拒绝未回退 nextIndex → 无新写入时滞后
副本永不追平。修复：心跳不匹配时递减 nextIndex 并触发数据 flush
（`RaftNode.sendHeartbeatAsync`），回归测试
`newLeaderBackfillsLaggingFollowerWithoutNewWrites`。

## 8. 复现

- 迁移：`mvn -Dtest=ZeroCopyMigrationBenchmarkTest test`
- Multi-Raft：`mvn -Dtest=MultiRaftBenchmarkTest test`
- 输出前缀：`PHASE16-BENCH`
