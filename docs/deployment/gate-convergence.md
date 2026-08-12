# 真实执行门禁收敛表（ADR-0163）

## 门禁状态

| 编号 | 门禁 | 状态 | 阻塞原因 | 预期消除 |
| --- | --- | --- | --- | --- |
| TD-048 | CI 容器 E2E + 故障注入 | 交付物就绪 | 需 Linux Runner | Phase 36 |
| TD-049 | 真实块设备磁盘混沌 | 交付物就绪 | 需 Linux Runner | Phase 36 |
| K8S-001 | kind 集群验证 | 脚本就绪 | 需 Linux Runner | Phase 36 |
| REL-001 | release.yml（v1.1–v1.8） | 流水线就绪 | 需真实 tag 触发 | Phase 36 |
| BM-001 | 跨机 Production Benchmark | 本地口径完成 | 需跨机 Runner | Phase 36 |
| BM-002 | 跨地域 RTT/RTO/RPO | 进程内完成 | 需跨机 Runner | Phase 36 |
| TD-051/054 | 跨地域 2PC/联邦/流量/自治 | 进程内完成 | 需跨机 Runner | Phase 36 |

## 本阶段已执行（JVM 级）

- `Phase35ProductionGateTest`：自治回滚/日预算/地域上限、物化 stale 标记、
  跨驻留拒绝、法规版本切换、成本建议、隔离默认拒绝、SLO 违约；
- `Phase35EdgeMatrixTest`：参数化边缘矩阵（22 方法，80 项展开）。

## 原则

可执行项全绿 + 未执行项精确登记阻塞原因与预期消除阶段，禁止伪报完成。
