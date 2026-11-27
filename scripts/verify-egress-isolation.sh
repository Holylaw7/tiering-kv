#!/usr/bin/env bash
set -euo pipefail

# Wave 3A (S2 + S3) 运行态容器安全与纵深出口隔离实测脚本。
#
# 验证清单：
#   1. 容器非 root 运行（id -u != 0）、PID 1 为真实业务进程（exec 优雅停机）与 HEALTHCHECK 健康；
#   2. Nginx 响应头包含严格 CSP 与全部安全头，且 /healthz 未被覆盖丢失安全头；
#   3. 四类 Webhook 出口连通性实测（在 CONSOLE_WEBHOOK_BLOCK_PRIVATE_URLS=true 下）：
#      (a) 公网允许目标（默认 https://example.com/webhook）：通过登记并成功发起固定 IP + TLS 建连；
#      (b) 私网 RFC1918 与容器内网主机名（10/8、172.16/12、192.168/16、tiering-kv）：一律阻断；
#      (c) 云元数据地址（AWS/GCP/Azure 169.254.169.254、阿里云 100.100.100.200、IPv4-mapped IPv6）：一律阻断；
#      (d) DNS 异常/不存在域名（nonexistent.invalid）：fail-closed 阻断。
#
# 用法：
#   bash scripts/verify-egress-isolation.sh --mode compose --base-url http://127.0.0.1:8081 [--token <JWT>]
#   bash scripts/verify-egress-isolation.sh --mode k8s --namespace tiering-kv --pod <console-server-pod>

MODE="compose"
BASE_URL="${EGRESS_BASE_URL:-http://127.0.0.1:8081}"
TOKEN="${EGRESS_AUTH_TOKEN:-}"
PUBLIC_URL="${EGRESS_PUBLIC_URL:-https://example.com/tieringkv-egress-probe}"
K8S_NAMESPACE="${EGRESS_K8S_NAMESPACE:-tiering-kv}"
K8S_POD="${EGRESS_K8S_POD:-}"
SKIP_PUBLIC="${EGRESS_SKIP_PUBLIC:-false}"

while [[ $# -gt 0 ]]; do
  case "$1" in
    --mode) MODE="$2"; shift 2 ;;
    --base-url) BASE_URL="$2"; shift 2 ;;
    --token) TOKEN="$2"; shift 2 ;;
    --public-url) PUBLIC_URL="$2"; shift 2 ;;
    --namespace) K8S_NAMESPACE="$2"; shift 2 ;;
    --pod) K8S_POD="$2"; shift 2 ;;
    --skip-public) SKIP_PUBLIC="true"; shift 1 ;;
    *) echo "Unknown argument: $1" >&2; exit 2 ;;
  esac
done

verify_compose_containers() {
  echo "[egress] 1/3 Checking non-root UID, PID 1 exec, and HEALTHCHECK on running containers"
  local containers=("tk-core" "tk-console" "tk-web")
  if docker inspect tk-console-2 >/dev/null 2>&1; then
    containers+=("tk-console-2")
  fi
  for c in "${containers[@]}"; do
    local uid pid1 health
    uid=$(docker exec "$c" id -u)
    pid1=$(docker exec "$c" sh -c 'cat /proc/1/comm')
    health=$(docker inspect -f '{{if .State.Health}}{{.State.Health.Status}}{{else}}none{{end}}' "$c")
    echo "  container=$c uid=$uid pid1=$pid1 health=$health"
    if [[ "$uid" == "0" ]]; then
      echo "FAIL: container $c is running as root (uid=0)" >&2
      exit 1
    fi
    if [[ "$c" == "tk-web" ]]; then
      if [[ "$pid1" != "nginx" ]]; then
        echo "FAIL: container $c PID 1 is '$pid1', expected 'nginx'" >&2
        exit 1
      fi
    else
      if [[ "$pid1" != "java" ]]; then
        echo "FAIL: container $c PID 1 is '$pid1', expected 'java' (missing exec)" >&2
        exit 1
      fi
    fi
    if [[ "$health" != "healthy" ]]; then
      echo "FAIL: container $c health status is '$health', expected 'healthy'" >&2
      exit 1
    fi
  done
}

verify_nginx_headers() {
  echo "[egress] 2/3 Checking Nginx CSP & security headers on / and /healthz"
  for path in "/" "/healthz"; do
    local headers
    headers=$(curl -fsS -D - -o /dev/null "${BASE_URL}${path}" | tr -d '\r')
    for expected in \
      "content-security-policy:" \
      "x-content-type-options: nosniff" \
      "x-frame-options: deny" \
      "referrer-policy: strict-origin-when-cross-origin" \
      "permissions-policy:"; do
      if ! grep -qi "^${expected}" <<< "$headers"; then
        echo "FAIL: missing header '${expected}' on ${BASE_URL}${path}" >&2
        echo "$headers" >&2
        exit 1
      fi
    done
    echo "  verified security headers + CSP on ${path}"
  done
}

verify_webhook_egress_matrix() {
  echo "[egress] 3/3 Verifying 4-scenario Webhook egress matrix (public / private / IMDS / DNS error)"
  if [[ -z "$TOKEN" ]]; then
    TOKEN=$(curl -fsS -X POST "${BASE_URL}/api/v1/auth/login" \
      -H "Content-Type: application/json" \
      -d '{"username":"admin","password":"admin123"}' \
      | python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["token"])')
  fi
  test -n "$TOKEN"

  local blocked_targets=(
    "private-10|http://10.255.255.1/hook"
    "private-172|http://172.16.0.1/hook"
    "private-192|http://192.168.1.1/hook"
    "private-docker-core|http://tiering-kv:9100/healthz"
    "imds-aws-gcp-azure|http://169.254.169.254/latest/meta-data/"
    "imds-alibaba-cgnat|http://100.100.100.200/latest/meta-data/"
    "imds-ipv4-mapped-v6|http://[::ffff:169.254.169.254]/latest/meta-data/"
  )

  for entry in "${blocked_targets[@]}"; do
    local label="${entry%%|*}"
    local url="${entry#*|}"
    local resp_file
    resp_file=$(mktemp)
    local http_code
    http_code=$(curl -sS -o "$resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/alerts/channels" \
      -H "Authorization: Bearer ${TOKEN}" \
      -H "Content-Type: application/json" \
      -d "{\"name\":\"egress-probe-${label}\",\"type\":\"WEBHOOK\",\"url\":\"${url}\",\"silenceSeconds\":60,\"enabled\":true}")
    local body
    body=$(cat "$resp_file")
    rm -f "$resp_file"
    echo "  [BLOCKED CHECK] ${label} (${url}) -> HTTP ${http_code}"
    if [[ "$http_code" != "400" ]]; then
      echo "FAIL: expected HTTP 400 for blocked target ${label} (${url}), got ${http_code}: ${body}" >&2
      exit 1
    fi
  done

  # Scenario 4: DNS failure / NXDOMAIN fail-closed check (registration may allow pre-provisioning,
  # while actual delivery via OutboundUrlGuard.resolveForDelivery MUST fail closed with statusCode=0)
  local dns_url="https://nonexistent.invalid/webhook"
  local dns_resp_file dns_http
  dns_resp_file=$(mktemp)
  dns_http=$(curl -sS -o "$dns_resp_file" -w '%{http_code}' -X POST "${BASE_URL}/api/v1/alerts/channels" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"egress-probe-dns-$(date +%s)\",\"type\":\"WEBHOOK\",\"url\":\"${dns_url}\",\"silenceSeconds\":60,\"enabled\":true}")
  local dns_body
  dns_body=$(cat "$dns_resp_file")
  rm -f "$dns_resp_file"
  if [[ "$dns_http" == "400" ]]; then
    echo "  [DNS FAIL-CLOSED CHECK] ${dns_url} -> blocked at registration (HTTP 400)"
  elif [[ "$dns_http" == "200" ]]; then
    local dns_ch_id
    dns_ch_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])' <<< "$dns_body")
    local dns_test_resp dns_delivered dns_status dns_msg
    dns_test_resp=$(curl -fsS -X POST "${BASE_URL}/api/v1/alerts/channels/${dns_ch_id}/test" \
      -H "Authorization: Bearer ${TOKEN}")
    dns_delivered=$(python3 -c 'import json,sys; print(str(json.load(sys.stdin)["data"]["delivered"]).lower())' <<< "$dns_test_resp")
    dns_status=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["statusCode"])' <<< "$dns_test_resp")
    dns_msg=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["message"])' <<< "$dns_test_resp")
    curl -fsS -X DELETE -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/alerts/channels/${dns_ch_id}" >/dev/null || true
    echo "  [DNS FAIL-CLOSED CHECK] ${dns_url} -> delivery blocked (delivered=${dns_delivered}, statusCode=${dns_status}, msg=${dns_msg})"
    if [[ "$dns_delivered" != "false" ]] || [[ "${dns_status:-1}" != "0" ]]; then
      echo "FAIL: DNS NXDOMAIN delivery was not blocked fail-closed: ${dns_test_resp}" >&2
      exit 1
    fi
  else
    echo "FAIL: unexpected HTTP ${dns_http} for DNS NXDOMAIN check: ${dns_body}" >&2
    exit 1
  fi

  if [[ "$SKIP_PUBLIC" == "true" ]]; then
    echo "  [PUBLIC CHECK] skipped (--skip-public)"
    return 0
  fi

  # Scenario 1: Public allowed target must pass OutboundUrlGuard, resolve once, pin IP, complete TLS, and get an HTTP status
  local ch_name="egress-probe-public-$(date +%s)"
  local create_resp
  create_resp=$(curl -fsS -X POST "${BASE_URL}/api/v1/alerts/channels" \
    -H "Authorization: Bearer ${TOKEN}" \
    -H "Content-Type: application/json" \
    -d "{\"name\":\"${ch_name}\",\"type\":\"WEBHOOK\",\"url\":\"${PUBLIC_URL}\",\"silenceSeconds\":60,\"enabled\":true}")
  local ch_id
  ch_id=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["id"])' <<< "$create_resp")
  test -n "$ch_id"

  local test_resp
  test_resp=$(curl -fsS -X POST "${BASE_URL}/api/v1/alerts/channels/${ch_id}/test" \
    -H "Authorization: Bearer ${TOKEN}")
  local status_code msg
  status_code=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["statusCode"])' <<< "$test_resp")
  msg=$(python3 -c 'import json,sys; print(json.load(sys.stdin)["data"]["message"])' <<< "$test_resp")
  echo "  [PUBLIC CHECK] ${PUBLIC_URL} -> remote HTTP statusCode=${status_code} msg=${msg}"

  # Clean up probe channel regardless of assertion outcome
  curl -fsS -X DELETE -H "Authorization: Bearer ${TOKEN}" "${BASE_URL}/api/v1/alerts/channels/${ch_id}" >/dev/null || true

  if [[ "${status_code:-0}" -lt 200 ]]; then
    echo "FAIL: expected public target ${PUBLIC_URL} to reach remote server (statusCode >= 200), got statusCode=${status_code} msg=${msg}" >&2
    exit 1
  fi
}

verify_k8s_egress() {
  if [[ -z "$K8S_POD" ]]; then
    K8S_POD=$(kubectl -n "$K8S_NAMESPACE" get po -l app.kubernetes.io/component=console-server -o jsonpath='{.items[0].metadata.name}')
  fi
  test -n "$K8S_POD"
  echo "[egress-k8s] Probing CNI NetworkPolicy egress from pod ${K8S_NAMESPACE}/${K8S_POD}"

  # 1. Public HTTPS allowed
  if [[ "$SKIP_PUBLIC" != "true" ]]; then
    kubectl -n "$K8S_NAMESPACE" exec "$K8S_POD" -- curl -fsS --max-time 5 -o /dev/null "$PUBLIC_URL" \
      || { echo "FAIL: public HTTPS target unreachable from ${K8S_POD}" >&2; exit 1; }
    echo "  [OK] public target reachable"
  fi

  # 2. Cloud metadata blocked at network layer
  for meta_ip in "169.254.169.254" "100.100.100.200"; do
    if kubectl -n "$K8S_NAMESPACE" exec "$K8S_POD" -- curl -fsS --max-time 3 "http://${meta_ip}/latest/meta-data/" >/dev/null 2>&1; then
      echo "FAIL: cloud metadata ${meta_ip} was reachable from ${K8S_POD}" >&2
      exit 1
    fi
    echo "  [OK] cloud metadata ${meta_ip} blocked"
  done

  # 3. Private non-whitelisted RFC1918 blocked
  if kubectl -n "$K8S_NAMESPACE" exec "$K8S_POD" -- curl -fsS --max-time 3 "http://10.255.255.1:80/" >/dev/null 2>&1; then
    echo "FAIL: non-whitelisted private IP 10.255.255.1 was reachable from ${K8S_POD}" >&2
    exit 1
  fi
  echo "  [OK] non-whitelisted private RFC1918 blocked"

  # 4. DNS NXDOMAIN fails closed
  if kubectl -n "$K8S_NAMESPACE" exec "$K8S_POD" -- curl -fsS --max-time 3 "https://nonexistent.invalid/" >/dev/null 2>&1; then
    echo "FAIL: nonexistent.invalid unexpectedly succeeded from ${K8S_POD}" >&2
    exit 1
  fi
  echo "  [OK] DNS NXDOMAIN fail-closed"
}

case "$MODE" in
  compose)
    verify_compose_containers
    verify_nginx_headers
    verify_webhook_egress_matrix
    echo "[egress] ALL COMPOSE SECURITY & 4-SCENARIO EGRESS CHECKS PASSED"
    ;;
  k8s)
    verify_k8s_egress
    echo "[egress-k8s] ALL K8S NETWORKPOLICY EGRESS CHECKS PASSED"
    ;;
  *)
    echo "Unsupported --mode: $MODE (expected compose or k8s)" >&2
    exit 2
    ;;
esac
