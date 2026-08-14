#!/usr/bin/env bash
set -euo pipefail

# Kubernetes 集群内验证（ADR-0102）：kind 拉起 → 应用清单 → 演练 → cleanup。
# 用法：TIERINGKV_KIND_CLUSTER=true scripts/kind-e2e.sh run

CLUSTER=${TIERINGKV_KIND_CLUSTER_NAME:-tiering-kv}

case "${1:-run}" in
  run)
    # workflow 的 helm/kind-action 通常已预建同名校集群；
    # 独立运行脚本时才创建，避免与预建集群冲突。
    if ! kind get clusters | grep -qx "$CLUSTER"; then
      kind create cluster --name "$CLUSTER"
    fi
    kind load docker-image ghcr.io/tiering-kv/tiering-kv:v3.7.0 \
      --name "$CLUSTER"
    kubectl create namespace tiering-kv || true
    kubectl -n tiering-kv create secret generic tiering-kv-secrets \
      --from-literal=admin-password=ci \
      --from-literal=cluster-auth-token=ci \
      --from-literal=backup-encryption-key=ci || true
    kubectl -n tiering-kv apply -f deploy/kubernetes/tiering-kv/
    kubectl -n tiering-kv rollout status statefulset/tiering-kv-meta \
      --timeout=180s
    kubectl -n tiering-kv rollout status statefulset/tiering-kv-storage \
      --timeout=180s
    kubectl -n tiering-kv rollout status deployment/tiering-kv-gateway \
      --timeout=120s
    touch target/kind-cluster-ready
    # 冒烟：gateway Service 端口转发 + RESP SET/GET
    kubectl -n tiering-kv port-forward svc/tiering-kv-gateway 6379:6379 &
    PF_PID=$!
    sleep 3
    printf '*3\r\n$3\r\nSET\r\n$2\r\nk1\r\n$2\r\nv1\r\n' \
      | timeout 5 bash -c 'cat > /dev/tcp/127.0.0.1/6379'
    printf '*2\r\n$3\r\nGET\r\n$2\r\nk1\r\n' \
      | timeout 5 bash -c 'cat > /dev/tcp/127.0.0.1/6379'
    kill "$PF_PID" 2>/dev/null || true
    touch target/kind-gateway-smoke
    ;;
  pdb-drain)
    kubectl -n tiering-kv drain "$(kubectl get nodes -o name | head -1)" \
      --ignore-daemonsets --delete-emptydir-data --force 2>/dev/null || true
    kubectl -n tiering-kv rollout status statefulset/tiering-kv-meta \
      --timeout=120s
    ;;
  cleanup)
    kind delete cluster --name "$CLUSTER"
    rm -f target/kind-cluster-ready target/kind-gateway-smoke
    ;;
  *)
    echo "unknown command: ${1}" >&2
    exit 1
    ;;
esac
