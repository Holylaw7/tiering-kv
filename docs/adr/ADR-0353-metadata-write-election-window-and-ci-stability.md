# ADR-0353: Metadata Write Election-Window Bounded Wait & CI Stability Hardening

## Status

Accepted

## Context

v4.1.0 收尾期间，纯文档提交 f30e714 触发的真实 Runner 门禁偶发两类
环境 flaky 失败（rerun-failed-jobs 后恢复，非代码回归）：

1. **MetadataPersistenceTest `no metadata leader`**（test 分片）：
   `MetadataRaftGroup.write` 在 `leader() == null` 时 fail-fast 抛异常；
   慢 Runner 的 CPU 调度使 Raft 心跳延迟超过选举超时（100±80ms），
   1100 次连续写入命中“旧 leader 退位 → 新 leader 未产生”的窗口；
2. **RealBlockDeviceExerciseTest `diskFullAppendFailsWithoutLoss`**
   （block-device-chaos）：probe（1×4096B 新块）失败证明磁盘已满，
   但断言负载（新建目录 + 小 WAL 段 + 5 条小值，总量 <1 块）可能被
   inline_data / 已分配元数据块 / 延迟分配吸收，不触发 ENOSPC；
3. **分片策略**：重型分布式包集中在 shard 0/1，单一 shard 的时序
   敏感测试密度偏高，放大选举窗口类 flake。

## Decision

**产品层吸收选举窗口 + 测试层消除 ENOSPC 语义缝隙 + CI 分片加固**：

1. `MetadataRaftGroup.write` 改为有界等待 leader（默认 1000ms，
   10ms 轮询），超时才抛 `no metadata leader`；中断恢复中断位；
   既有的“propose 失败后换新 leader 重试一次”逻辑保留。等待有界，
   满足 Phase 20「禁止客户端永久悬挂」约束；
2. `RealBlockDeviceExerciseTest` 满盘断言要求 **≥3 个新数据块分配**：
   - 新增与断言同尺度的 16KB 多块探针（必须 IOException）；
   - WAL 负载改为 64 条 × 512B（≈32KB，≥8 个新块），必须失败；
   - javadoc 明确“ENOSPC 断言必须要求多块分配，小负载不可靠”；
3. `shard-tests.sh` 改为全量 3 分片轮转（NR % 3）：重型分布式包
   （cluster/transaction/mvcc/replication/runtime 等）不再集中于
   shard 0/1，降低单一 shard 时序敏感测试密度；surefire
   `rerunFailingTestsCount=1` 保留为兜底，不作为通过依据。

## Alternatives

1. 测试层无限重试 / 放宽超时：掩盖窗口而不修复产品 fail-fast，
   慢 Runner 下客户端仍会瞬时失败；
2. 只改分片不动 write：选举窗口仍存在，只是被分摊概率；
3. 小负载 ENOSPC 断言保留 + 重跑兜底：无法根治文件系统分配语义
   的不确定性。

## Consequences

优点：

- 元数据客户端在 failover 期间从“瞬时报错”变为“有界等待自动恢复”，
  生产健壮性提升；
- 满盘断言与文件系统真实语义对齐（多块分配必然 ENOSPC）；
- 分片负载更均匀，时序敏感 flake 概率显著下降。

缺点：

- write 在极端无 leader 场景最坏增加 1s 等待（有界，可接受）；
- 分片策略改变后 shard 运行时间需 1–2 轮 Runner 观察校准。

风险：

- 低。变更面限定在写路径等待逻辑、块设备测试负载与分片脚本；
  全量回归 + 真实 Runner 门禁验证。

## Implementation

- `MetadataRaftGroup.write` + `awaitLeader`（有界等待）；
- 新增 `MetadataRaftGroupWriteTest`（未启动组等待 1s 后失败 /
  leader 故障切换窗口写入自动恢复）；
- `RealBlockDeviceExerciseTest`（多块负载 + 同粒度探针）；
- `scripts/shard-tests.sh`（3 分片全量轮转）；
- 本 ADR；CHANGELOG；ROADMAP 技术债/收尾记录。
