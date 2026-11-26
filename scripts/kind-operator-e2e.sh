#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# Tiering-KV Operator 真实 kind 集群全生命周期 E2E（v5.4.0 A3）
# 验证闭环：
#   1. 安装 CRD / RBAC / Operator Deployment，断言 /healthz + /readyz + Lease 选主成功；
#   2. 应用 TieringKVCluster CR（meta=1, storage=2, gw=1），断言 phase=READY 且 RESP SET/GET 成功；
#   3. 在线扩容 storageReplicas 2 -> 3（验证 StatefulSet 安全 Patch 无 422），断言 r3 区域与 3/3 就绪；
#   4. 滚动升级 spec.image v1 -> v2，断言全部工作负载镜像切换且重回 READY + RESP 读写可用；
#   5. 注解触发按需备份 Job，断言 Job 成功（Succeeded=1）且回写 status.lastBackupStatus=SUCCEEDED；
#   6. 删除 CR 触发 Finalizer，断言子工作负载全部清理且全部 PVC 完好保留。

CLUSTER="${TIERINGKV_OPERATOR_KIND_CLUSTER:-tiering-kv-operator}"
NS="tiering-kv"
CR_NAME="demo"
IMG_V1="ghcr.io/holylaw7/tiering-kv:e2e-v1"
IMG_V2="ghcr.io/holylaw7/tiering-kv:e2e-v2"
OP_IMG="ghcr.io/holylaw7/tiering-kv-operator:e2e"

wait_cr_condition() {
  local desc="$1"
  local timeout_sec="$2"
  local expr="$3"
  local deadline=$((SECONDS + timeout_sec))
  while [ "$SECONDS" -lt "$deadline" ]; do
    if eval "$expr"; then
      echo "[operator-e2e] OK: $desc"
      return 0
    fi
    sleep 3
  done
  echo "[operator-e2e] TIMEOUT waiting for: $desc" >&2
  dump_diagnostics
  return 1
}

dump_diagnostics() {
  echo "=== Operator & Cluster Diagnostics ===" >&2
  kubectl -n "$NS" get tkc,sts,deploy,po,svc,pvc,cj,job,lease -o wide >&2 || true
  kubectl -n "$NS" describe po -l app=tiering-kv-operator >&2 || true
  kubectl -n "$NS" logs -l app=tiering-kv-operator --tail=120 >&2 || true
  kubectl -n "$NS" get tkc "$CR_NAME" -o yaml >&2 || true
  kubectl -n "$NS" describe po -l tieringkv.io/cluster="$CR_NAME" >&2 || true
}
trap dump_diagnostics ERR

smoke_resp() {
  local local_port="$1"
  kubectl -n "$NS" port-forward "svc/${CR_NAME}-gateway" "${local_port}:6379" >/tmp/pf-operator.log 2>&1 &
  local pf_pid=$!
  trap 'kill "$pf_pid" 2>/dev/null || true' RETURN
  sleep 3
  TIERINGKV_GATEWAY_HOST=127.0.0.1 \
    TIERINGKV_GATEWAY_PORT="$local_port" \
    TIERINGKV_SMOKE_ATTEMPTS=15 \
    scripts/container-smoke.sh
  kill "$pf_pid" 2>/dev/null || true
  wait "$pf_pid" 2>/dev/null || true
  trap - RETURN
}

case "${1:-run}" in
  run)
    echo "[operator-e2e] 1/6 Install CRD, RBAC & Operator Deployment"
    kubectl apply -f deploy/operator/tieringkvcluster-crd.yaml
    kubectl apply -f deploy/operator/rbac.yaml
    sed -E "s|ghcr\.io/holylaw7/tiering-kv-operator:v[^[:space:]\"]+|${OP_IMG}|g" \
      deploy/operator/deployment.yaml | kubectl apply -f -
    kubectl -n "$NS" rollout status deployment/tiering-kv-operator --timeout=150s

    wait_cr_condition "Operator acquired K8s Lease" 60 '
      holder=$(kubectl -n "$NS" get lease tiering-kv-operator-leader -o jsonpath="{.spec.holderIdentity}" 2>/dev/null || true)
      test -n "$holder" && echo "[operator-e2e] lease holder=$holder"
    '

    echo "[operator-e2e] 2/6 Apply TieringKVCluster CR (meta=1, storage=2, gw=1) & verify RESP"
    sed -E "s|ghcr\.io/holylaw7/tiering-kv:v[^[:space:]\"]+|${IMG_V1}|g" \
      deploy/operator/kind-cluster-sample.yaml | kubectl apply -f -

    wait_cr_condition "CR demo READY (1/2/1)" 180 '
      phase=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.phase}" 2>/dev/null || true)
      rm=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.readyMetadata}" 2>/dev/null || true)
      rs=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.readyStorage}" 2>/dev/null || true)
      rg=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.readyGateway}" 2>/dev/null || true)
      echo "  poll: phase=$phase ready=${rm:-0}/${rs:-0}/${rg:-0}"
      [ "$phase" = "READY" ] && [ "${rm:-0}" = "1" ] && [ "${rs:-0}" = "2" ] && [ "${rg:-0}" = "1" ]
    '
    smoke_resp 16379

    echo "[operator-e2e] 3/6 Online scale storageReplicas 2 -> 3"
    kubectl -n "$NS" patch tkc "$CR_NAME" --type=merge -p '{"spec":{"storageReplicas":3}}'
    wait_cr_condition "CR demo scaled to storage=3 and READY" 180 '
      phase=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.phase}" 2>/dev/null || true)
      rs=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.readyStorage}" 2>/dev/null || true)
      sts_ready=$(kubectl -n "$NS" get sts "${CR_NAME}-storage" -o jsonpath="{.status.readyReplicas}" 2>/dev/null || true)
      echo "  scale poll: phase=$phase readyStorage=${rs:-0} stsReady=${sts_ready:-0}"
      [ "$phase" = "READY" ] && [ "${rs:-0}" = "3" ] && [ "${sts_ready:-0}" = "3" ]
    '
    kubectl -n "$NS" get cm "${CR_NAME}-config" -o jsonpath='{.data.regions\.conf}' | grep -q "${CR_NAME}-storage-2 r3"
    echo "[operator-e2e] regions.conf auto-expanded with r3"

    echo "[operator-e2e] 4/6 Rolling upgrade image v1 -> v2"
    kubectl -n "$NS" patch tkc "$CR_NAME" --type=merge -p "{\"spec\":{\"image\":\"${IMG_V2}\"}}"
    wait_cr_condition "All workloads upgraded to e2e-v2 and READY" 180 '
      phase=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.phase}" 2>/dev/null || true)
      gen=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.metadata.generation}" 2>/dev/null || true)
      obs=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.observedGeneration}" 2>/dev/null || true)
      m_img=$(kubectl -n "$NS" get sts "${CR_NAME}-meta" -o jsonpath="{.spec.template.spec.containers[0].image}" 2>/dev/null || true)
      s_img=$(kubectl -n "$NS" get sts "${CR_NAME}-storage" -o jsonpath="{.spec.template.spec.containers[0].image}" 2>/dev/null || true)
      g_img=$(kubectl -n "$NS" get deploy "${CR_NAME}-gateway" -o jsonpath="{.spec.template.spec.containers[0].image}" 2>/dev/null || true)
      echo "  upgrade poll: phase=$phase gen=${gen:-0} obs=${obs:-0} imgs=${m_img}|${s_img}|${g_img}"
      [ "$phase" = "READY" ] && [ "${obs:-0}" = "${gen:-1}" ] && \
        [ "$m_img" = "$IMG_V2" ] && [ "$s_img" = "$IMG_V2" ] && [ "$g_img" = "$IMG_V2" ]
    '
    kubectl -n "$NS" rollout status "sts/${CR_NAME}-meta" --timeout=120s
    kubectl -n "$NS" rollout status "sts/${CR_NAME}-storage" --timeout=120s
    kubectl -n "$NS" rollout status "deploy/${CR_NAME}-gateway" --timeout=120s
    smoke_resp 16380

    echo "[operator-e2e] 5/6 Trigger on-demand backup Job via CR annotation"
    kubectl -n "$NS" annotate tkc "$CR_NAME" tieringkv.io/backup-request-id=e2e-1 --overwrite
    wait_cr_condition "On-demand backup job demo-backup-e2e-1 SUCCEEDED" 120 '
      succ=$(kubectl -n "$NS" get job "${CR_NAME}-backup-e2e-1" -o jsonpath="{.status.succeeded}" 2>/dev/null || true)
      bstat=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.lastBackupStatus}" 2>/dev/null || true)
      bjob=$(kubectl -n "$NS" get tkc "$CR_NAME" -o jsonpath="{.status.lastBackupJob}" 2>/dev/null || true)
      echo "  backup poll: job_succeeded=${succ:-0} status=${bstat:-} job=${bjob:-}"
      [ "${succ:-0}" = "1" ] && [ "$bstat" = "SUCCEEDED" ] && [ "$bjob" = "${CR_NAME}-backup-e2e-1" ]
    '
    backup_log=$(kubectl -n "$NS" logs "job/${CR_NAME}-backup-e2e-1")
    echo "$backup_log"
    echo "$backup_log" | grep -q "ping=+PONG"
    echo "$backup_log" | grep -Eq "dbsize=:[1-9][0-9]*"
    echo "$backup_log" | grep -q "bgsave=+Background saving started"
    echo "[operator-e2e] verified real distributed DBSIZE>=1 & BGSAVE success"

    echo "[operator-e2e] 6/6 Delete CR via Finalizer & assert PVCs retained"
    pvc_before=$(kubectl -n "$NS" get pvc -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d ' ')
    echo "[operator-e2e] PVCs before delete: $pvc_before"
    test "${pvc_before:-0}" -ge 4

    kubectl -n "$NS" delete tkc "$CR_NAME" --timeout=120s

    wait_cr_condition "Owned workloads removed after CR deletion" 60 '
      sts_n=$(kubectl -n "$NS" get sts -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      dep_n=$(kubectl -n "$NS" get deploy -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      svc_n=$(kubectl -n "$NS" get svc -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      cj_n=$(kubectl -n "$NS" get cj -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      job_n=$(kubectl -n "$NS" get job -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      cm_n=$(kubectl -n "$NS" get cm -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d " ")
      [ "$sts_n" = "0" ] && [ "$dep_n" = "0" ] && [ "$svc_n" = "0" ] && [ "$cj_n" = "0" ] && [ "$job_n" = "0" ] && [ "$cm_n" = "0" ]
    '
    pvc_after=$(kubectl -n "$NS" get pvc -l "tieringkv.io/cluster=${CR_NAME}" -o name | wc -l | tr -d ' ')
    echo "[operator-e2e] PVCs after delete: $pvc_after (expected >= $pvc_before)"
    test "${pvc_after:-0}" -ge "$pvc_before"
    echo "[operator-e2e] ALL 6 LIFECYCLE STAGES PASSED!"
    ;;
  cleanup)
    kind delete cluster --name "$CLUSTER" || true
    ;;
  *)
    echo "Usage: $0 {run|cleanup}" >&2
    exit 1
    ;;
esac
