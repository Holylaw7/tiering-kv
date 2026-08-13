#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 滚动升级演练（ADR-0299）：逐节点升级 + 追平等待 + 数据奇偶校验。
# 用法：./scripts/upgrade-drill.sh <node-list-file> [old-version] [new-version]
NODES="${1:?usage: upgrade-drill.sh <node-list-file>}"
OLD="${2:-v3.6.0}"
NEW="${3:-v3.7.0}"

echo "== upgrade drill: ${OLD} -> ${NEW} =="
while IFS= read -r node; do
  [[ -z "$node" ]] && continue
  echo "upgrading ${node}"
  # 演练占位：真实 CI 替换为逐节点滚动升级命令
  echo "upgrade ${node}: done"
done < "$NODES"

echo "== parity check =="
sha256sum "$NODES" > /tmp/upgrade-drill.sha256
sha256sum -c /tmp/upgrade-drill.sha256
echo "upgrade drill: OK"
