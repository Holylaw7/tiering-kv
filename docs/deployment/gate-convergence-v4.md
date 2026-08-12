# 真实执行门禁收敛表 v4（ADR-0178）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入 | 交付物就绪 | 需 Linux Runner | Phase 39 |
| TD-049 | 真实块设备磁盘混沌 | 交付物就绪 | 需 Linux Runner | Phase 39 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 39 |
| REL-001 | release.yml（v1.1–v2.1） | 流水线就绪 | 需真实 tag 触发 | Phase 39 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 39 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨机 Runner | Phase 39 |
| TD-051/054/059/060/063 | 跨地域 2PC/联邦/流量/自治 | 进程内完成 | 需跨机 Runner | Phase 39 |

## 本阶段已执行（JVM 级）

- `Phase38ProductionGateTest`：持久化/强化学习/生命周期/签名/迁移/
  风险评分门禁；
- `Phase38EdgeMatrixTest`：参数化矩阵（56 方法）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段，禁止伪报完成。
