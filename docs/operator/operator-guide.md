# Kubernetes Operator 指南

Phase 26 · ADR-0107

## 1. CRD

```yaml
apiVersion: tieringkv.io/v1
kind: TieringKVCluster
spec:
  metadataReplicas: 3   # 必须为奇数（Raft quorum）
  storageReplicas: 3
  regionIds: [r1, r2, r3]
  image: tiering-kv:1.0.0
  backupScheduleCron: "0 2 * * *"
```

## 2. 控制循环

```text
applySpec → OperatorPlanner（desired vs current）
          → 动作（CREATE/SCALE/UPGRADE/BACKUP，按优先级）
          → actionSink（kind/kubectl 执行）
```

## 3. 自动能力

- 空集群创建；metadata/storage 扩缩容；
- 镜像升级（滚动，quorum 保持）；备份计划触发。

## 4. 限制

- 控制器为计划引擎 + 脚本绑定，未引入 fabric8；
- 多集群/多租户为后续版本。
