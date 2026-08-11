# Phase 24 混沌测试报告

Phase 24 · 2026-08-11 · 范围：事务运行时云原生发布

## 1. 混沌场景矩阵

| 场景 | 注入方式 | 验证结果 |
| --- | --- | --- |
| 节点 kill（coordinator） | 重建 Router 后 recover | ✅ 已提交事务不丢失 |
| 节点 kill（participant） | 同端口重启 + 重新注册 handler | ✅ 提交/恢复正常 |
| 网络分区 | 提交路径注入瞬时 RuntimeException + recover | ✅ 无幻影、无丢失 |
| Metadata leader 故障 | Raft 组 suspend+close → 新 leader 提案 | ✅ 多数派 apply |
| Disk Full | StorageEngine.put 抛异常（JVM 等价） | ✅ 提交拒绝、回滚安全 |
| Readonly | StorageEngine.put 抛异常 | ✅ 无部分可见 |
| Slow IO | put 延迟 1–25ms | ✅ 提交成功、无双主 |
| 多轮故障 | 3 轮 disk-full/readonly 交替 + recover | ✅ 无提交丢失 |
| 滚动升级 | 逐节点 suspend/close + 追平等待 | ✅ quorum 保持、升级中止安全 |

## 2. 新增混沌测试

- `RealDiskChaosTest`（11 项）：disk full / readonly / slow / 参数化失败点；
- `RealDiskChaosParameterizedTest`（29 项）：多键、多轮故障、恢复期故障、
  大 value、tombstone、显式回滚；
- `CiTransactionE2ETest` + `CiTransactionE2EParameterizedTest`（30 项）：
  TCP 全链路正常/故障路径；
- `MetadataMultiRaftTest`（14 项）+ `MetadataSnapshotStateTest`（41 项）：
  选举、故障转移、快照损坏/截断容忍。

## 3. 关键结论

1. **提交拒绝优先于丢数据**：disk full / readonly 下事务失败后回滚安全，
   restart+recover 后已提交数据完整；
2. **恢复幂等**：recover 可重入，失败事务不会产生部分可见版本；
3. **快照抗损坏**：条目尾截断与生命周期尾截断均容忍，坏长度前缀安全
   跳过；
4. **RPC 大负载**：修复 64KB 长度前缀溢出后，1MB value 往返一致。

## 4. 登记技术债

| 编号 | 内容 | 状态 |
| --- | --- | --- |
| TD-048 | CI 容器 E2E 执行 | 工作流交付，待 Linux Runner 触发 |
| TD-049 | 真实容器磁盘混沌（dmsetup/fio/fallocate） | 矩阵交付，待 Runner 注入 |
| TD-050 | 元数据 Multi-Raft 网络化传输 | 待跨机验证 |
