# Kubernetes 集群内验证指南（kind）

Phase 25 · 2026-08-11 · ADR-0102

## 1. 环境

```bash
kind create cluster --name tiering-kv
docker build -t tiering-kv:3.7.0 -f deploy/Dockerfile .
kind load docker-image tiering-kv:3.7.0
```

## 2. 一键验证

```bash
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh run
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh pdb-drain
TIERINGKV_KIND_CLUSTER=true mvn -q -Dtest=KubernetesInClusterValidationTest test
TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh cleanup
```

脚本覆盖：命名空间/Secret → 应用全部清单 → metadata/storage StatefulSet
rollout 就绪 → gateway Deployment 就绪 → 6379 端口转发 RESP SET/GET 冒烟
→ PDB 驱逐演练（drain 节点后 StatefulSet 恢复）。

## 3. 门控测试

`KubernetesInClusterValidationTest`（`TIERINGKV_KIND_CLUSTER=true` 时运行）：

- target/kind-cluster-ready：集群与 StatefulSet 就绪标记；
- target/kind-gateway-smoke：网关冒烟标记；
- 清单仍然存在（防漂移）。

## 4. 限制

- kind 无云 LB/存储类，PVC 使用默认 storageClass；
- PDB 驱逐演练为单节点集群近似，多 AZ 行为需托管集群复核；
- Secret 使用 CI 占位值，生产必须替换。
