#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 版本一致性校验（ADR-0262）：pom revision 必须出现在 release notes /
# CHANGELOG / README / ROADMAP / release-notes.sh 中。
REVISION=$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' pom.xml \
  | head -n 1)
if [[ -z "$REVISION" ]]; then
  echo "version-check: cannot find <revision> in pom.xml" >&2
  exit 1
fi
MAJOR_MINOR_PATCH="${REVISION%%-*}"

for file in CHANGELOG.md README.md ROADMAP.md \
  docs/release/v3.6.0-release-notes.md scripts/release-notes.sh; do
  if ! grep -q "${MAJOR_MINOR_PATCH}" "$file"; then
    echo "version-check: ${file} missing ${MAJOR_MINOR_PATCH}" >&2
    exit 1
  fi
done

echo "version-check: OK (${REVISION})"
