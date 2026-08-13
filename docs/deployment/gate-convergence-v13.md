# 真实执行门禁收敛表 v13（ADR-0241）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入（3 连绿） | 交付物就绪 | 需 Linux Runner | Phase 48 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪 | 需 Linux Runner | Phase 48 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 48 |
| REL-001 | release.yml（v1.1–v3.0）真实运行记录 | 流水线就绪 | 需真实 tag 触发 | Phase 48 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 48 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨地域 Runner | Phase 48 |
| TD-051/054/059/060/063 | 跨地域真实基准 | 进程内完成 | 需跨地域 Runner | Phase 48 |
| TD-066/069/072/075/078 | Phase 38–42 真实执行门禁 | 登记完成 | 需对应 Runner/tag | Phase 48 |
| TD-076 | S3/Spot 真实网络凭据验证 | 配额握手 JVM 已绿 | 真实网络待验证 | Phase 48 |
| TD-079 | 跨云一阶段全球统一仲裁 | ✅ JVM 全绿 | — | Phase 47 |
| TD-080 | RL 动态下推 | ✅ JVM 全绿 | — | Phase 47 |

## 本阶段 JVM 级执行

- `Phase47ProductionGateTest`：统一仲裁 / RL / 量子授时 / 监管证书 /
  凭据 v5 / 门禁注册表 / 执行归档；
- `Phase47EdgeMatrixTest`：参数化边缘矩阵；
- `Phase47BenchmarkTest` + `Phase47ProductionBaselineTest`：跨机回归
  告警口径基线 + TiKV 对比表；
- `GateConvergenceV13Test`：收敛表登记校验 + RunnerExecutionArchive。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段；执行记录由
`RunnerExecutionArchive` 归档，登记数据由 `GateConvergenceV13`
（src/main/java/io/tieringkv/ci）统一维护。
