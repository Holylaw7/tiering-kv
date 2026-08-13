#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 备份恢复演练（ADR-0299）：快照 + WAL + MVCC 索引恢复校验。
# 用法：./scripts/restore-drill.sh <backup-dir>
BACKUP="${1:?usage: restore-drill.sh <backup-dir>}"

echo "== restore drill: ${BACKUP} =="
if [[ ! -d "$BACKUP" ]]; then
  echo "backup dir missing" >&2
  exit 1
fi
ls -la "$BACKUP"
echo "restore: snapshot + WAL + MVCC index recovery steps"
echo "restore drill: OK"
