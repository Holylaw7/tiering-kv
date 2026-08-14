# ADR-0322: v4 M4 Production Closure

## Status

Accepted

## Context

v4.0 M4（docs/planning/v4-roadmap.md）：生产收口。现状：

- Operator：`TieringKVCluster` CRD + Planner/Controller 已有基础，
  备份/恢复、滚动升级、多集群编排未完整；
- Jepsen harness（ADR-0306）骨架已交付，未外部化到真实 Runner；
- 性能报告以内存/局部口径为主，缺 cold-cache 与真实 Runner 三级
  基线（TD-009）；
- 容量模型（TD-019）未落地为可计算/可验证模型。

## Decision

### 1. Operator 完整化

- `TieringKVCluster` 状态机补齐：Provisioning → Ready → Upgrading →
  BackingUp/Restoring → Ready；
- Controller reconcile 覆盖：备份/恢复任务、滚动升级（逐节点 +
  追平等待 + quorum 保护）、多集群编排（跨集群复制 CRD 接线）；
- 与既有 `deploy/kubernetes` 清单保持同源（additive）。

### 2. Jepsen 外部化

- harness 脚本化：`scripts/jepsen-run.sh`（分区/网络注入/断言/报告）；
- 真实 Runner job 执行 + artifact 归档。

### 3. 性能基线（冷/热口径）

- 三级基准：A 内存 / B 服务端 / C 生产全链路；
- 冷口径：模拟 cold-cache（新进程/清页缓存语义）与热口径分开报告；
- 真实 Runner 数据 + 本地复现脚本。

### 4. 容量模型

- `CapacityModel`：输入（QPS/值大小/副本/保留策略）→ 输出
  （吞吐预算/延迟预算/内存/磁盘），可计算可测试；
- 报告与基准数据联动（TD-019 关闭方向）。

## Alternatives

1. Operator 延后：K8s 能力停留在清单级，无状态机；
2. Jepsen 继续骨架：无真实故障注入证据；
3. 容量模型仅文档：不可计算不可验证。

## Consequences

优点：M4 交付可运行、可验证、可归档。

缺点：Operator/Jepsen 工程量大，与 P1 技术债竞争资源。

风险：真实 Runner 冷口径受 runner 环境限制（无 root drop cache），
以"新进程冷启动 + 本地 root 脚本"双口径记录。

## Implementation

```text
docs/planning/optimization-roadmap.md
.codex/tasks/phase61-v4-m4-production-closure.md
capacity/  CapacityModel（+测试）
scripts/   jepsen-run.sh、cold-cache-bench.sh
operator/  reconcile 状态机扩展（备份/恢复/滚动升级）
```

验收：GA 门禁 7/7 ×2 + Jepsen 报告 + 容量模型 + Operator E2E。
