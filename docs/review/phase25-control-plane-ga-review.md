# Phase 25 评审报告：Control-Plane GA Closure

Phase 25 · 2026-08-11 · v1.0 GA 门槛

## 1. 结论

Phase 25 完成 v1.0 GA 门槛的核心项：

- **TD-050 关闭**：事务元数据 Multi-Raft 网络化——TxnMetadataNode 接入
  MultiRaftEndpoint 共享传输，FileRaftLog + RaftPersistentState +
  SnapshotManager 落盘，Raft-first + decisionIndex 语义不变；
- **TD-048 交付物闭环**：CI 容器 E2E 管道补齐容器故障注入（kill
  coordinator/participant/metadata + tc netem 分区），真实 Runner 执行
  待 GitHub Actions 触发；
- **TD-049 交付物闭环**：真实块设备混沌脚本（loop device + dmsetup +
  fio + remount,ro）与门控测试就绪，Linux Runner 执行待触发；
- **K8s 集群内验证交付物**：kind-e2e.sh 覆盖清单应用、StatefulSet 就绪、
  PDB 驱逐与网关冒烟，门控测试就绪。

新增 **170 项测试**（2408 运行全绿 + 6 项容器门控测试本地正确跳过），
全量 **2408/2408 PASS**（目标 ≥2400 ✅）。

## 2. ADR

| ADR | 主题 |
| --- | --- |
| 0099 | Metadata Multi-Raft Network Transport |
| 0100 | CI Container Chaos Pipeline |
| 0101 | Block Device Disk Chaos |
| 0102 | Kubernetes In-Cluster Validation |

## 3. 关键实现

1. **META_PROPOSE / META_STATUS RPC**：MultiRaftEndpoint 新增组提案与
   状态查询，RpcServer 增加异步响应支持（提案不阻塞事件循环）；
2. **网络化元数据组**：TxnMetadataNode 生产构造接入 MultiRaftTransport，
   日志/状态/快照落盘；TxnMetadataClient 网络模式按节点轮询 leader，
   非 leader 返回重定向错误；
3. **快照字节化**：MetadataSnapshotManager 提供 serialize/loadInto，
   Raft SnapshotManager 状态机快照复用；copyFrom 支持运行时快照安装替换；
4. **测试发现并修正**：增量恢复测试明确"新版本必须在恢复用存储中"；
   tombstone 测试修正为基于 HLC 实际提交时间戳（旧 tombstone 不遮蔽
   新值，符合 MVCC 语义）。

## 4. 基准（进程内 TCP，Windows localhost，如实标注）

| 指标 | 结果 |
| --- | --- |
| 元数据提案（单写者 100/300/600） | 657–1077 ops/s |
| 元数据提案（并发 1/4/8 写者） | 724 / 1169 / 1393 ops/s |
| 元数据 leader failover | 110–118ms |
| 节点重启恢复（200 条日志） | ≈1.27s（含 1s 端口等待） |
| 快照重启恢复（1100 条日志） | ≈1.25s（含 1s 端口等待） |

注：提案路径含 FileRaftLog SYNC 持久化 + 三节点复制 + 决策 apply，
吞吐为 JVM 进程内 TCP 等价，跨机数值以 CI/裸机为准。

## 5. 测试与混沌覆盖

| 模块 | 用例 | 覆盖 |
| --- | ---: | --- |
| Metadata Network Raft | 41 | 选举/提案/重定向/failover/持久化重启/分区追平/快照/并发 |
| Snapshot 字节层 | 33 | 全状态/生命周期/损坏容忍/大负载/替换语义 |
| 磁盘混沌扩展 | 28 | disk full/readonly/slow/多轮故障/HLC tombstone |
| CI E2E 扩展 | 20 | 跨区键数/GET/重启后 MSET/回滚/分区恢复/覆盖写 |
| Health / Upgrade | 10 | 超时矩阵/中断/异常传播 |
| Backup 扩展 | 10 | 多版本/主键尺寸/生命周期/增量恢复 |
| K8s 清单 | 15 | 环境变量/探针/PVC/PDB/终止宽限/选择器/资源 |
| Final Benchmark | 9 | 提案吞吐/failover/重启/快照/并发 |

## 6. 局限（不隐藏）

1. TD-048/049 与 K8s 集群内验证的执行依赖 Linux + Docker Runner，
   交付物（工作流、脚本、门控测试）已就绪，真实执行记录待 CI 触发；
2. 元数据提案吞吐（约 1K ops/s）受 FileRaftLog SYNC + 三节点复制 +
   全链路 RPC 影响，为决策面保守基线；
3. 容器故障注入脚本中 tc netem 依赖容器内 iproute2，部分基础镜像
   需预装。

## 7. 下一步

- 触发 GitHub Actions：jvm-e2e / container-e2e（含故障注入）/
  kind-e2e / block-device-chaos 四个 job；
- 跨机元数据组基准（真实网卡 RTT 下的提案吞吐与 failover）；
- v1.0.0 发布候选版本号冻结与发布演练。
