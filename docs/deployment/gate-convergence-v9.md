# 真实执行门禁收敛表 v9（ADR-0213）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入（3 连绿） | 交付物就绪 | 需 Linux Runner | Phase 44 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪 | 需 Linux Runner | Phase 44 |
| K8S-001 | kind 集群验证（StatefulSet/PDB/网关冒烟/备份恢复） | 脚本就绪 | 需 Linux Runner | Phase 44 |
| REL-001 | release.yml（v1.1–v2.6）真实运行记录 | 流水线就绪 | 需真实 tag 触发 | Phase 44 |
| BM-001 | 跨机 Production Benchmark（Gateway×3/Metadata×3/Storage×6） | 本地口径完成 | 需跨机 Runner | Phase 44 |
| BM-002 | 跨地域 RTT/RTO/RPO/冲突率/收敛时间 | 进程内完成 | 需跨地域 Runner | Phase 44 |
| TD-051/054 | 跨地域真实 2PC/联邦基准 | 进程内完成 | 需跨地域 Runner | Phase 44 |
| TD-059/060/063 | 跨地域流量/自治/复制基准 | 进程内完成 | 需跨地域 Runner | Phase 44 |
| TD-066/069/072/075/078 | Phase 38–42 真实执行门禁 | 登记完成 | 需对应 Runner/tag | Phase 44 |
| TD-076 | S3/Spot 真实凭据/网络验证 | JVM 探测已绿 | 真实网络待验证 | Phase 44 |
| TD-079 | 跨区一阶段提交 | ✅ JVM 全绿 | — | Phase 43 |
| TD-080 | Coprocessor 多算子联合下推 | ✅ JVM 全绿 | — | Phase 43 |

## 本阶段 JVM 级执行

- `Phase43ProductionGateTest`：跨区一阶段 / 多算子链 / TSO / 自治联动 /
  凭据探测 / 门禁注册表（30 项）；
- `Phase43EdgeMatrixTest`：参数化边缘矩阵（64 项）；
- `Phase43BenchmarkTest` + `Phase43ProductionBaselineTest`：吞吐与延迟基线；
- `GateConvergenceV9Test`：收敛表登记校验（60 项，禁止伪报）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段；登记数据由
`GateConvergenceV9`（src/main/java/io/tieringkv/ci）统一维护，
任何「已完成」声明必须有对应 JVM 测试或真实执行记录佐证。
