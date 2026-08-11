# ADR-0107: Kubernetes Operator Design

## Status

Accepted

## Context

Phase 24 交付 K8s 清单（StatefulSet/PDB/Gateway），运维动作（扩容、
故障替换、升级、备份）需人工执行。v1.0 需要声明式控制平面：
`TieringKVCluster` CRD + Controller + Reconciler。

## Decision

新增 `deploy/operator/` 与 `operator/`：

1. CRD `tieringkv.io/v1` `TieringKVCluster`：spec（metadata 副本 /
   storage 副本 / region 映射 / 镜像 / 备份策略）；
2. `OperatorPlanner`：desired vs current → 动作计划（create / scale /
   replace / upgrade / backup）；
3. `Controller`：周期性 reconcile，调用 planner 并生成目标清单；
4. 集群 API 绑定经 kind/kubectl 脚本执行（不引入 fabric8 依赖，
   控制循环与计划逻辑可单测）。

## Alternatives

1. 引入 fabric8/operator-sdk：依赖重、构建复杂，超出 JVM 单模块；
2. 纯脚本：无声明式状态与自动收敛。

## Consequences

优点：运维动作收敛到可测试的计划引擎；CRD 提供声明式入口。

缺点：真实集群绑定需 kind/kubectl 执行；多集群/多租户为后续版本。

风险：planner 决策需与 PDB/滚动升级语义一致，测试覆盖。

## Implementation

代码影响范围：

- `deploy/operator/`（CRD + sample）；
- `operator/`（ClusterSpec / OperatorPlanner / Controller）；
- `OperatorPlanTest`、`OperatorManifestTest` 与
  `docs/operator/operator-guide.md`。
