#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
version="${1:?usage: release.sh <version>}"
if [[ ! "$version" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
  echo "version must match MAJOR.MINOR.PATCH (e.g. 3.7.0)" >&2
  exit 1
fi
if [[ -n "$(git status --porcelain)" ]]; then
  echo "working tree is not clean" >&2
  exit 1
fi
echo "release v${version} prerequisites OK (full pipeline lands in Phase 10)"
