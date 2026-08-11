# Kubernetes 生产部署指南

Phase 24 · 2026-08-11

## 1. 架构

```text
Client
  |
  v
tiering-kv-gateway (Deployment, 2 副本, :6379)
  |
  v
tiering-kv-meta (StatefulSet, 3 副本, Raft :7300)
  |
  v
tiering-kv-storage (StatefulSet, 3 副本, participant :7100)
```

## 2. 清单

`deploy/kubernetes/tiering-kv/`：

| 文件 | 内容 |
| --- | --- |
| StatefulSet.yaml | 元数据 3 副本 + 存储 3 副本（PVC + 探针） |
| Service.yaml | 元数据/存储 Headless + Gateway ClusterIP(:6379) |
| ConfigMap.yaml | tiering-kv.yaml + start.sh + regions.conf |
| Secret.yaml | admin-password / cluster-auth-token / backup-encryption-key |
| PodDisruptionBudget.yaml | 元数据/存储 minAvailable=2（保 quorum） |
| Gateway.yaml | Gateway Deployment 2 副本 |

## 3. 部署

```bash
kubectl create namespace tiering-kv
kubectl -n tiering-kv create secret generic tiering-kv-secrets \
  --from-literal=admin-password=... \
  --from-literal=cluster-auth-token=... \
  --from-literal=backup-encryption-key=...
kubectl -n tiering-kv apply -f deploy/kubernetes/tiering-kv/
```

## 4. 探针与优雅停机

- readiness：TCP 端口可连（metadata 7300 / storage 7100 / gateway 6379）；
- liveness：TCP 存活探测；容器 `terminationGracePeriodSeconds=60`；
- SIGTERM → stop accept → drain inflight → flush raft → close（ADR-0096）。

## 5. 滚动升级

1. 构建新镜像并更新 image tag；
2. StatefulSet 默认滚动：一次替换一个 Pod，PDB 保证至少 2 个副本存活；
3. 升级后等待 caught-up（`UpgradeCoordinator`，ADR-0098）；
4. 失败时升级中止，剩余副本保持 quorum。

## 6. 备份 / 恢复

- 备份：元数据快照（txn-meta.snap）+ MVCC 索引（mvcc.index）（ADR-0097）；
- 恢复：`RestoreManager.restoreMetadata` + `restoreMvcc`；
- 生产建议：定时快照 + WAL 增量重放，备份加密密钥使用 Secret。

## 7. 限制

- 清单未包含 Ingress/NLB 与持久化存储类参数，按集群环境调整
  `volumeClaimTemplates` 的 storageClassName；
- 跨 AZ 部署建议使用 topology spread constraints；
- 真实集群 e2e（kind/k3s）待 CI 环境执行。
