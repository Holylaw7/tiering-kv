# ADR-0102: Kubernetes In-Cluster Validation

## Status

Accepted

## Context

Phase 24 交付 K8s 清单（StatefulSet/Service/ConfigMap/Secret/PDB/Gateway）
并通过 10 项结构测试，但未在真实集群验证运行时行为（探针时序、PVC 绑定、
Headless DNS、PDB 驱逐、滚动升级、备份恢复）。

## Decision

使用 kind（或 k3s）在 CI 执行集群内验证：

1. 应用全部清单，等待 StatefulSet 3/3 Ready、Headless DNS 可解析；
2. gateway SET/GET 冒烟 + metadata leader failover；
3. PDB 驱逐演练：drain 一个节点，quorum 保持；
4. 滚动升级演练：逐 Pod 替换，升级中写入不丢失；
5. 备份恢复演练：PVC 重建 → restore → 事务可读。

## Alternatives

1. 真实云 K8s：成本与权限门槛高，不适合默认 CI；
2. 仅结构测试：无法覆盖探针/调度/存储生命周期。

## Consequences

优点：清单运行时行为获得验证；kind 可在 ubuntu-latest 上低成本执行。

缺点：kind 与托管集群存在差异（LB/存储类），仍需生产集群复核。

风险：镜像拉取与节点就绪时序，需等待循环与日志采集。

## Implementation

代码影响范围：

- `scripts/kind-e2e.sh`（集群创建 → 应用清单 → 演练 → cleanup）；
- `.github/workflows/transaction-e2e.yml`（增加 kind job）；
- `src/test/java/io/tieringkv/deploy/KubernetesManifestTest.java`
  （补充运行时约束断言）。
