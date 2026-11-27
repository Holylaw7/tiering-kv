#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."

# SBOM/签名/归档（ADR-0316，v6.4.0 D3 升级支持零依赖 CycloneDX 1.5 JSON）：
#   1. 向后兼容纯文本模式：bash scripts/sbom.sh [target/sbom.txt]
#   2. CycloneDX 1.5 JSON 模式：
#      bash scripts/sbom.sh --cdx [target/tiering-kv-sbom.cdx.json]
#      bash scripts/sbom.sh --cdx --offline [target/tiering-kv-sbom.cdx.json]

MODE="text"
OFFLINE="false"
OUT=""

for arg in "$@"; do
  case "$arg" in
    --cdx|--cyclonedx)
      MODE="cdx"
      ;;
    --offline)
      OFFLINE="true"
      ;;
    *)
      OUT="$arg"
      ;;
  esac
done

if [[ -z "$OUT" ]]; then
  if [[ "$MODE" == "cdx" ]]; then
    OUT="target/tiering-kv-sbom.cdx.json"
  else
    OUT="target/sbom.txt"
  fi
fi

if [[ "$OUT" == *.json ]]; then
  MODE="cdx"
fi

mkdir -p "$(dirname "$OUT")"

if [[ "$MODE" == "text" ]]; then
  {
    echo "== SBOM =="
    mvn -B -q dependency:list -DoutputFile=target/deps.txt
    find . -path '*/target/deps.txt' -exec cat {} +
    echo "== checksums =="
    find . -path '*/target/*.jar' ! -path './console-web/*' ! -name 'original-*' -exec sha256sum {} + || true
  } > "$OUT"
  echo "sbom: $OUT"
  exit 0
fi

# CycloneDX 1.5 JSON 模式（零第三方 Maven 插件，纯 Python3 标准库组装）
PYTHON_BIN="python3"
if ! command -v "$PYTHON_BIN" >/dev/null 2>&1; then
  PYTHON_BIN="python"
fi

MVN_DEPS_FILE="target/sbom-maven-deps.txt"
: > "$MVN_DEPS_FILE"

if [[ "$OFFLINE" != "true" ]] && command -v mvn >/dev/null 2>&1; then
  mvn -B -q dependency:list -DoutputFile=target/deps-module.txt >/dev/null 2>&1 || true
  find . -maxdepth 3 -path './tiering-*/target/deps-module.txt' -exec cat {} + >> "$MVN_DEPS_FILE" || true
  if [[ -f console-server/pom.xml ]]; then
    (cd console-server && mvn -B -q dependency:list -DoutputFile=target/deps-console.txt >/dev/null 2>&1 || true)
    [[ -f console-server/target/deps-console.txt ]] && cat console-server/target/deps-console.txt >> "$MVN_DEPS_FILE"
  fi
fi

"$PYTHON_BIN" - "$OUT" "$MVN_DEPS_FILE" <<'PY'
import datetime
import glob
import hashlib
import json
import os
import re
import sys
import urllib.parse
import uuid
import xml.etree.ElementTree as ET

out_path = sys.argv[1]
mvn_deps_path = sys.argv[2]

def read_revision():
    tree = ET.parse("pom.xml")
    root = tree.getroot()
    ns = {"m": root.tag.split("}")[0].strip("{")} if "}" in root.tag else {}
    prefix = "m:" if ns else ""
    rev = root.findtext(f"{prefix}properties/{prefix}revision", namespaces=ns)
    return (rev or "0.0.0").strip()

def parse_pom_dependencies():
    """Fallback/offline direct dependency extraction from all Maven POMs."""
    props = {}
    pom_files = ["pom.xml"] + sorted(glob.glob("tiering-*/pom.xml"))
    if os.path.exists("console-server/pom.xml"):
        pom_files.append("console-server/pom.xml")

    for pf in pom_files:
        root = ET.parse(pf).getroot()
        ns_uri = root.tag.split("}")[0].strip("{") if "}" in root.tag else ""
        ns = {"m": ns_uri} if ns_uri else {}
        p = "m:" if ns else ""
        prop_node = root.find(f"{p}properties", ns)
        if prop_node is not None:
            for child in prop_node:
                key = child.tag.split("}")[-1]
                if child.text:
                    props[key] = child.text.strip()
        parent_ver = root.findtext(f"{p}parent/{p}version", namespaces=ns)
        if parent_ver and pf == "console-server/pom.xml":
            props.setdefault("spring-boot.version", parent_ver.strip())

    def resolve(val):
        if not val:
            return ""
        val = val.strip()
        for _ in range(4):
            m = re.findall(r"\$\{([^}]+)\}", val)
            if not m:
                break
            changed = False
            for k in m:
                if k in props:
                    val = val.replace("${" + k + "}", props[k])
                    changed = True
            if not changed:
                break
        return val

    found = []
    for pf in pom_files:
        root = ET.parse(pf).getroot()
        ns_uri = root.tag.split("}")[0].strip("{") if "}" in root.tag else ""
        ns = {"m": ns_uri} if ns_uri else {}
        p = "m:" if ns else ""
        for dep in root.findall(f".//{p}dependency", ns):
            gid = resolve(dep.findtext(f"{p}groupId", namespaces=ns))
            aid = resolve(dep.findtext(f"{p}artifactId", namespaces=ns))
            ver = resolve(dep.findtext(f"{p}version", namespaces=ns))
            scope = resolve(dep.findtext(f"{p}scope", namespaces=ns)) or "compile"
            if not gid or not aid or gid == "io.tieringkv":
                continue
            if scope == "test" or not ver or "${" in ver:
                continue
            found.append((gid, aid, ver, scope))
    return found

def parse_maven_list(path):
    found = []
    if not os.path.exists(path):
        return found
    # Matches standard Maven dependency:list lines:
    #   group:artifact:type:version:scope  or  group:artifact:type:classifier:version:scope
    pattern = re.compile(r"^\s*([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):([A-Za-z0-9_.-]+):(?:([A-Za-z0-9_.-]+):)?([^:\s]+):([a-z]+)")
    with open(path, encoding="utf-8", errors="ignore") as f:
        for line in f:
            m = pattern.match(line)
            if not m:
                continue
            gid, aid, _pkg, _cls, ver, scope = m.groups()
            if gid == "io.tieringkv" or scope == "test":
                continue
            found.append((gid, aid, ver, scope))
    return found

def parse_npm_lock(path):
    found = []
    if not os.path.exists(path):
        return found
    with open(path, encoding="utf-8") as f:
        data = json.load(f)
    packages = data.get("packages", {})
    for pkg_path, info in packages.items():
        if not pkg_path or not isinstance(info, dict):
            continue
        ver = info.get("version")
        if not ver:
            continue
        # Extract package name from last node_modules/ segment
        if "node_modules/" in pkg_path:
            name = pkg_path.rsplit("node_modules/", 1)[-1]
        else:
            name = info.get("name") or pkg_path
        is_dev = bool(info.get("dev"))
        found.append((name, ver, "optional" if is_dev else "required"))
    return found

def sha256_file(path):
    h = hashlib.sha256()
    with open(path, "rb") as f:
        for chunk in iter(lambda: f.read(65536), b""):
            h.update(chunk)
    return h.hexdigest()

revision = read_revision()
components_by_ref = {}

maven_deps = parse_maven_list(mvn_deps_path)
if not maven_deps:
    maven_deps = parse_pom_dependencies()

for gid, aid, ver, scope in maven_deps:
    purl = f"pkg:maven/{urllib.parse.quote(gid, safe='.-_')}/{urllib.parse.quote(aid, safe='.-_')}@{urllib.parse.quote(ver, safe='.-_+')}"
    components_by_ref[purl] = {
        "type": "library",
        "bom-ref": purl,
        "group": gid,
        "name": aid,
        "version": ver,
        "scope": "optional" if scope in ("provided", "system") else "required",
        "purl": purl,
    }

for name, ver, scope in parse_npm_lock("console-web/package-lock.json"):
    if name.startswith("@"):
        ns_part, pkg_part = name.split("/", 1)
        purl = f"pkg:npm/{urllib.parse.quote(ns_part, safe='')}/{urllib.parse.quote(pkg_part, safe='.-_')}@{urllib.parse.quote(ver, safe='.-_+')}"
    else:
        purl = f"pkg:npm/{urllib.parse.quote(name, safe='.-_')}@{urllib.parse.quote(ver, safe='.-_+')}"
    components_by_ref[purl] = {
        "type": "library",
        "bom-ref": purl,
        "name": name,
        "version": ver,
        "scope": scope,
        "purl": purl,
    }

# Include built internal JAR artifacts with SHA-256 digests when present
for jar in sorted(glob.glob("**/target/*.jar", recursive=True)):
    norm = jar.replace("\\", "/")
    base = os.path.basename(norm)
    if norm.startswith("console-web/") or base.startswith("original-") or base.endswith("-tests.jar"):
        continue
    artifact = base[:-4]
    purl = f"pkg:maven/io.tieringkv/{urllib.parse.quote(artifact, safe='.-_')}@{urllib.parse.quote(revision, safe='.-_+')}"
    components_by_ref[purl] = {
        "type": "application",
        "bom-ref": purl,
        "group": "io.tieringkv",
        "name": artifact,
        "version": revision,
        "scope": "required",
        "purl": purl,
        "hashes": [{"alg": "SHA-256", "content": sha256_file(jar)}],
    }

bom = {
    "bomFormat": "CycloneDX",
    "specVersion": "1.5",
    "serialNumber": f"urn:uuid:{uuid.uuid4()}",
    "version": 1,
    "metadata": {
        "timestamp": datetime.datetime.now(datetime.timezone.utc).strftime("%Y-%m-%dT%H:%M:%SZ"),
        "tools": [
            {
                "vendor": "Tiering-KV",
                "name": "scripts/sbom.sh",
                "version": revision,
            }
        ],
        "component": {
            "type": "application",
            "bom-ref": f"pkg:maven/io.tieringkv/tiering-kv@{revision}",
            "group": "io.tieringkv",
            "name": "tiering-kv",
            "version": revision,
            "purl": f"pkg:maven/io.tieringkv/tiering-kv@{revision}",
        },
    },
    "components": [components_by_ref[k] for k in sorted(components_by_ref)],
}

with open(out_path, "w", encoding="utf-8", newline="\n") as f:
    json.dump(bom, f, indent=2, ensure_ascii=False)
    f.write("\n")

print(f"cyclonedx-sbom: {out_path} (components={len(bom['components'])})")
PY
