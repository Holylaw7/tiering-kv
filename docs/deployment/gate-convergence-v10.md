# 真实执行门禁收敛表 v10（ADR-0220）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入（3 连绿） | 交付物就绪 | 需 Linux Runner | Phase 45 |
| TD-049 | 真实块设备磁盘混沌（loop/dmsetup/fio/remount） | 交付物就绪 | 需 Linux Runner | Phase 45 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 45 |
| REL-001 | release.yml（v1.1–v2.7）真实运行记录 | 流水线就绪 | 需真实 tag 触发 | Phase 45 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 45 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨地域 Runner | Phase 45 |
| TD-051/054/059/060/063 | 跨地域真实基准 | 进程内完成 | 需跨地域 Runner | Phase 45 |
| TD-066/069/072/075/078 | Phase 38–42 真实执行门禁 | 登记完成 | 需对应 Runner/tag | Phase 45 |
| TD-076 | S3/Spot 真实凭据/网络验证 | 真实 HTTP 探针 JVM 已绿 | 真实网络待验证 | Phase 45 |
| TD-079 | 全局一阶段提交规模化 | ✅ JVM 全绿 | — | Phase 44 |
| TD-080 | Coprocessor 全算子联合下推 | ✅ JVM 全绿 | — | Phase 44 |

## 本阶段 JVM 级执行

- `Phase44ProductionGateTest`：全局一阶段 / 全算子 / TSO 容灾 /
  全自动 PD / 凭据 v2 / 门禁注册表（25 项）；
- `Phase44EdgeMatrixTest`：参数化边缘矩阵（25 项）；
- `Phase44BenchmarkTest` + `Phase44ProductionBaselineTest`：D 级
  分布式全链路基线 + TiKV 对比口径；
- `GateConvergenceV10Test`：收敛表登记校验（45 项，禁止伪报）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段；登记数据由
`GateConvergenceV10`（src/main/java/io/tieringkv/ci）统一维护。
