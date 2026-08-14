#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# hotfix 流程（ADR-0311）：fix/<desc> 分支 + 校验。
DESC="${1:?usage: hotfix.sh <desc>}"
BRANCH="fix/${DESC}"
git checkout -b "${BRANCH}" develop
echo "hotfix branch: ${BRANCH}"
echo "完成后：mvn -q test && git commit && 合并 develop/main"
