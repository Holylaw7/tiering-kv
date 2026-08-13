# 真实执行门禁收敛表 v11（ADR-0227）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入（3 连绿） | 交付物就绪 | 需 Linux Runner | Phase 46 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪 | 需 Linux Runner | Phase 46 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 46 |
| REL-001 | release.yml（v1.1–v2.8）真实运行记录 | 流水线就绪 | 需真实 tag 触发 | Phase 46 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 46 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨地域 Runner | Phase 46 |
| TD-051/054/059/060/063 | 跨地域真实基准 | 进程内完成 | 需跨地域 Runner | Phase 46 |
| TD-066/069/072/075/078 | Phase 38–42 真实执行门禁 | 登记完成 | 需对应 Runner/tag | Phase 46 |
| TD-076 | S3/Spot 真实网络凭据验证 | 认证握手 JVM 已绿 | 真实网络待验证 | Phase 46 |
| TD-079 | 跨云全局一阶段提交 | ✅ JVM 全绿 | — | Phase 45 |
| TD-080 | 多表 JOIN / 窗口函数下推 | ✅ JVM 全绿 | — | Phase 45 |

## 本阶段 JVM 级执行

- `Phase45ProductionGateTest`：跨云 / 窗口 / 成本模型 / 全球时钟 /
  无人值守 / 凭据 v3 / 门禁注册表；
- `Phase45EdgeMatrixTest`：参数化边缘矩阵；
- `Phase45BenchmarkTest` + `Phase45ProductionBaselineTest`：跨机口径
  基线 + TiKV 对比表；
- `GateConvergenceV11Test`：收敛表登记校验（禁止伪报）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段；登记数据由
`GateConvergenceV11`（src/main/java/io/tieringkv/ci）统一维护。
