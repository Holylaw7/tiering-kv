#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# SBOM/签名/归档（ADR-0316）：依赖清单 + 校验和。
OUT="${1:-target/sbom.txt}"
{
  echo "== SBOM =="
  mvn -q dependency:list -DoutputFile=target/deps.txt
  cat target/deps.txt
  echo "== checksums =="
  find target -name '*.jar' -exec sha256sum {} +
} > "$OUT"
echo "sbom: $OUT"
