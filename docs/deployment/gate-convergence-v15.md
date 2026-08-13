# Real Runner Gate Convergence v15

## 概述

门禁收敛表 v15（ADR-0255）对 Phase 48 遗留门禁给出唯一终态：

- CLOSED：JVM 级验证全绿（可执行项）；
- ENV_BLOCKED：交付物就绪，受环境限制未执行，阻塞原因精确登记；
- REGISTERED_RELEASE：依赖真实发布 tag 触发。

## 收敛表

| 门禁 | 状态 | 终态 | 阻塞原因 |
| --- | --- | --- | --- |
| TD-048 CI 容器 E2E | ENV_BLOCKED | ENV_BLOCKED | 需 Linux Runner |
| TD-049 块设备混沌 | ENV_BLOCKED | ENV_BLOCKED | 需 Linux Runner |
| K8S-001 kind 验证 | ENV_BLOCKED | ENV_BLOCKED | 需 Linux Runner |
| REL-001 发布记录 | REGISTERED_RELEASE | REGISTERED_RELEASE | 需真实 tag |
| BM-001 跨机基准 | ENV_BLOCKED | ENV_BLOCKED | 需跨机 Runner |
| BM-002 跨地域基准 | ENV_BLOCKED | ENV_BLOCKED | 需跨地域 Runner |
| TD-051/054/059/060/063/066/069/072/078 | ENV_BLOCKED | ENV_BLOCKED | 需 Runner |
| TD-075 发布门禁 | REGISTERED_RELEASE | REGISTERED_RELEASE | 需真实 tag |
| TD-076 S3/Spot 凭据 | GREEN_JVM | CLOSED | 延迟+抖动握手 JVM 闭环 |
| TD-079 多组织联邦 | GREEN_JVM | CLOSED | — |
| TD-080 多智能体下推 | GREEN_JVM | CLOSED | — |
| TD-081 跨监管域联邦 | GREEN_JVM | CLOSED | — |
| TD-082 联邦学习下推 | GREEN_JVM | CLOSED | — |
| TD-083 商用授时设备 | GREEN_JVM | CLOSED | — |
| TD-084 法规库/差异报告 | GREEN_JVM | CLOSED | — |

闭环归档：`RunnerClosureArchive` 记录终态快照、趋势点与告警历史，
`crossRegionTrendReport()` 导出跨地域趋势报表（未执行项标注 pending）。

## 使用

```bash
# 导出收敛表摘要
java -cp target/tiering-kv.jar io.tieringkv.ci.GateConvergenceV15
```

真实 Runner 就绪后，逐项执行并写入 `RunnerClosureArchive`，终态从
ENV_BLOCKED 迁移为 CLOSED。
