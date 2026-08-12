# 真实执行门禁收敛表 v2（ADR-0164）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入 | 交付物就绪 | 需 Linux Runner | Phase 37 |
| TD-049 | 真实块设备磁盘混沌 | 交付物就绪 | 需 Linux Runner | Phase 37 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 37 |
| REL-001 | release.yml（v1.1–v1.9） | 流水线就绪 | 需真实 tag 触发 | Phase 37 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 37 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨机 Runner | Phase 37 |
| TD-051/054/059 | 跨地域 2PC/联邦/流量/自治 | 进程内完成 | 需跨机 Runner | Phase 37 |

## 本阶段已执行（JVM 级）

- `Phase36ProductionGateTest`：自学习围栏/CDC 增量/证明链/调度约束/
  策略编译/SLO 预算门禁；
- `Phase36EdgeMatrixTest`：参数化矩阵（49 方法，177 项展开）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段，禁止伪报完成。
