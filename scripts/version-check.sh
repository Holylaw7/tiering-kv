#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# 版本一致性校验（ADR-0262）：pom revision 必须出现在 CHANGELOG /
# README / ROADMAP 与当前版本的发布说明中。
REVISION=$(sed -n 's/.*<revision>\(.*\)<\/revision>.*/\1/p' pom.xml \
  | head -n 1)
if [[ -z "$REVISION" ]]; then
  echo "version-check: cannot find <revision> in pom.xml" >&2
  exit 1
fi
MAJOR_MINOR_PATCH="${REVISION%%-*}"

RELEASE_NOTES="docs/release/v${MAJOR_MINOR_PATCH}-release-notes.md"

# 历史归档：v3.7.0 GA 的发布说明必须保留（frozen release gate，见
# ReleaseV37GATest）。这里只校验存在性，不参与当前版本号比对。
LEGACY_GA_NOTES="docs/release/v3.7.0-ga-release-notes.md"
if [[ ! -f "${LEGACY_GA_NOTES}" ]]; then
  echo "version-check: ${LEGACY_GA_NOTES} missing" >&2
  exit 1
fi
for file in CHANGELOG.md README.md ROADMAP.md "${RELEASE_NOTES}"; do
  if [[ ! -f "${file}" ]]; then
    echo "version-check: ${file} not found" >&2
    exit 1
  fi
  if ! grep -q "${MAJOR_MINOR_PATCH}" "${file}"; then
    echo "version-check: ${file} missing ${MAJOR_MINOR_PATCH}" >&2
    exit 1
  fi
done

echo "version-check: OK (${REVISION})"
