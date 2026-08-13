# 真实执行门禁收敛表 v14（ADR-0248）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入（3 连绿） | 交付物就绪 | 需 Linux Runner | Phase 49 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪 | 需 Linux Runner | Phase 49 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 49 |
| REL-001 | release.yml（v1.1–v3.1）真实运行记录 | 流水线就绪 | 需真实 tag 触发 | Phase 49 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 49 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨地域 Runner | Phase 49 |
| TD-051/054/059/060/063 | 跨地域真实基准 | 进程内完成 | 需跨地域 Runner | Phase 49 |
| TD-066/069/072/075/078 | Phase 38–42 真实执行门禁 | 登记完成 | 需对应 Runner/tag | Phase 49 |
| TD-076 | S3/Spot 真实网络凭据验证 | 延迟握手 JVM 已绿 | 真实网络待验证 | Phase 49 |
| TD-079 | 多组织联邦仲裁 | ✅ JVM 全绿 | — | Phase 48 |
| TD-080 | RL 多智能体下推 | ✅ JVM 全绿 | — | Phase 48 |

## 本阶段 JVM 级执行

- `Phase48ProductionGateTest`：联邦 / 多智能体 / 硬件适配 / 法规映射 /
  凭据 v6 / 门禁注册表 / 发布归档；
- `Phase48EdgeMatrixTest`：参数化边缘矩阵；
- `Phase48BenchmarkTest` + `Phase48ProductionBaselineTest`：跨机回归
  闭环口径基线 + TiKV 对比表；
- `GateConvergenceV14Test`：收敛表登记校验 + ReleaseRecordArchive。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段；发布记录由
`ReleaseRecordArchive` 归档，登记数据由 `GateConvergenceV14`
（src/main/java/io/tieringkv/ci）统一维护。
