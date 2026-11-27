#!/usr/bin/env python3
"""
Tiering-KV v6.5.0 Write-Path (WAL) Container Ablation Benchmark
Same host, same Docker network, same redis-benchmark protocol as exp_a_dataplane.py:
  - 1,000,000 keyspace x 64B payload, FLUSHALL before each sub-run
  - Compares Phase 1 (Sharded WAL 16 + zero-alloc), Phase 2 (ShardWorker batching 64),
    Phase 3 (dedicated WAL volume + preallocate + fdatasync + O_DIRECT vs NIO),
    and fsync=EVERY_SEC / NO / ALWAYS.
"""
import json
import re
import subprocess
import tempfile
import time
from pathlib import Path

TK_IMAGE = "tiering-kv-console-tiering-kv:latest"
REDIS_IMAGE = "redis:7-alpine"
NETWORK = "tkbench-v650"
VOL_NAME = "tkbench-v650-wal"


def run(cmd, check=True, timeout=300):
    res = subprocess.run(
        cmd,
        stdout=subprocess.PIPE,
        stderr=subprocess.STDOUT,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {' '.join(cmd)}\n{res.stdout}")
    return res.stdout or ""


def cleanup_container(name):
    subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def cleanup_volume(vol):
    subprocess.run(["docker", "volume", "rm", "-f", vol], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def wait_ready(name, timeout_s=60):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = subprocess.run(
            ["docker", "exec", name, "sh", "-c", "curl -fsS http://127.0.0.1:9100/healthz"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        if r.returncode == 0:
            return
        time.sleep(0.3)
    logs = run(["docker", "logs", "--tail", "50", name], check=False)
    raise RuntimeError(f"Container {name} failed to start:\n{logs}")


def write_cfg(cfg_dir: Path, fsync: str, shards: int, io_mode: str, prealloc: bool, batch_max: int):
    cfg_dir.mkdir(parents=True, exist_ok=True)
    yaml = f"""
server:
  host: 0.0.0.0
  port: 6379
  max-clients: 10000
  timeout-seconds: 0
worker:
  shard-count: 16
  flush-workers: 1
  migration-workers: 2
memory:
  limit: 1073741824
wal:
  fsync-policy: "{fsync}"
  max-segment-bytes: 16777216
  shards: {shards}
  io-mode: "{io_mode}"
  preallocate: {'true' if prealloc else 'false'}
  batch-max-entries: {batch_max}
cache:
  block-size: 1024
  eviction-policy: sampled-lfu
  eviction-samples: 16
tiering:
  low-watermark: 0.70
  high-watermark: 0.85
  critical-watermark: 0.95
observability:
  management-port: 9100
  slowlog-threshold-micros: 10000
  slowlog-max-len: 128
"""
    (cfg_dir / "application.yaml").write_text(yaml.strip() + "\n", encoding="utf-8")


def start_tk(name, cfg_dir: Path, mount_wal_vol: bool):
    cleanup_container(name)
    if mount_wal_vol:
        cleanup_volume(VOL_NAME)
        run(["docker", "volume", "create", VOL_NAME])
    java_opts = "-XX:+UseZGC -XX:+ZGenerational -Dio.netty.allocator.type=pooled -Xmx2g"
    cmd = [
        "docker", "run", "-d",
        "--name", name,
        "--network", NETWORK,
        "-v", f"{str(cfg_dir)}:/app/config:ro",
        "-w", "/app",
    ]
    if mount_wal_vol:
        cmd += ["-v", f"{VOL_NAME}:/app/data/wal"]
    cmd += [
        "--entrypoint", "sh",
        TK_IMAGE, "-c",
        f"exec java {java_opts} -cp /app/tiering-kv.jar io.tieringkv.Main",
    ]
    run(cmd)
    wait_ready(name)
    logs = run(["docker", "logs", name], check=False)
    wal_line = ""
    for line in logs.splitlines():
        if "WAL recovery:" in line or "O_DIRECT" in line or "effective configuration:" in line:
            wal_line += line + " | "
    return wal_line.strip()


def run_bench(host, clients, pipeline, requests, tests="set,get"):
    run(["docker", "run", "--rm", "--network", NETWORK, REDIS_IMAGE,
         "redis-cli", "-h", host, "-p", "6379", "FLUSHALL"], check=False)
    out = run([
        "docker", "run", "--rm", "--network", NETWORK, REDIS_IMAGE,
        "redis-benchmark", "-h", host, "-p", "6379",
        "-n", str(requests), "-c", str(clients), "-P", str(pipeline),
        "-r", "1000000", "-d", "64", "-t", tests,
    ], timeout=240)
    parsed = {}
    cur = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("====== SET"):
            cur = "SET"
            parsed[cur] = {}
        elif s.startswith("====== GET"):
            cur = "GET"
            parsed[cur] = {}
        elif cur:
            m = re.search(r"throughput summary:\s+([0-9.]+)\s+requests per second", s)
            if m:
                parsed[cur]["rps"] = float(m.group(1))
            m2 = re.match(r"^([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)$", s)
            if m2:
                parsed[cur]["avg_ms"] = float(m2.group(1))
                parsed[cur]["min_ms"] = float(m2.group(2))
                parsed[cur]["p50_ms"] = float(m2.group(3))
                parsed[cur]["p95_ms"] = float(m2.group(4))
                parsed[cur]["p99_ms"] = float(m2.group(5))
                parsed[cur]["max_ms"] = float(m2.group(6))
    return parsed


def fetch_wal_metrics(name):
    txt = run(["docker", "exec", name, "sh", "-c", "curl -fsS http://127.0.0.1:9100/metrics"], check=False)
    res = {}
    for line in txt.splitlines():
        if line.startswith("tieringkv_wal_"):
            parts = line.split()
            if len(parts) == 2:
                res[parts[0]] = parts[1]
    return res


def main():
    subprocess.run(["docker", "network", "rm", NETWORK], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run(["docker", "network", "create", NETWORK])
    results = {}
    name = "tk-v650-bench"
    try:
        with tempfile.TemporaryDirectory(prefix="tk-v650-") as tmp:
            tmp_path = Path(tmp)

            # Part 1: Staged Ablation on SET+GET c=50, P=16 (200,000 reqs)
            cases = [
                ("0_single_wal_b1_overlayfs", "EVERY_SEC", 1, "nio", False, 1, False, 200000, "set,get"),
                ("1_sharded16_b1_nio",        "EVERY_SEC", 16, "nio", False, 1, True, 200000, "set,get"),
                ("2_sharded16_b64_nio",       "EVERY_SEC", 16, "nio", False, 64, True, 200000, "set,get"),
                ("3a_sharded16_b64_prealloc_nio",    "EVERY_SEC", 16, "nio", True, 64, True, 200000, "set,get"),
                ("3b_sharded16_b64_prealloc_direct", "EVERY_SEC", 16, "direct", True, 64, True, 200000, "set,get"),
                ("3c_sharded16_b64_wal_no_nio",      "NO",        16, "nio", False, 64, True, 200000, "set,get"),
                ("4a_always_single_b1_nio",          "ALWAYS",    1,  "nio", False, 1, True, 20000,  "set"),
                ("4b_always_sharded16_b64_prealloc_nio",    "ALWAYS", 16, "nio", True, 64, True, 50000, "set"),
                ("4c_always_sharded16_b64_prealloc_direct", "ALWAYS", 16, "direct", True, 64, True, 50000, "set"),
            ]

            print("=== STAGED WAL ABLATION (c=50, P=16, 1M keys x 64B) ===", flush=True)
            for label, fsync, shards, io_mode, prealloc, batch_max, use_vol, reqs, tests in cases:
                cdir = tmp_path / label
                write_cfg(cdir, fsync, shards, io_mode, prealloc, batch_max)
                boot = start_tk(name, cdir, use_vol)
                # Warmup small burst
                run_bench(name, 16, 8, 20000, "set")
                bench = run_bench(name, 50, 16, reqs, tests)
                metrics = fetch_wal_metrics(name)
                results[label] = {"boot": boot, "bench": bench, "wal_metrics": metrics}
                s = bench.get("SET", {})
                g = bench.get("GET", {})
                print(
                    f"[{label}] SET={s.get('rps',0):,.0f} rps (p50={s.get('p50_ms',0):.3f}ms p99={s.get('p99_ms',0):.3f}ms max={s.get('max_ms',0):.3f}ms) "
                    f"| GET={g.get('rps',0):,.0f} rps | avgBatch={metrics.get('tieringkv_wal_avg_batch_size','?')} "
                    f"| backend={[k for k in metrics if k.startswith('tieringkv_wal_io_backend')]}",
                    flush=True,
                )
                cleanup_container(name)

            # Part 2: Pick fastest EVERY_SEC config and run full A1 matrix (c=1 P=1, c=50 P=1, c=200 P=1, c=50 P=16)
            best_label = max(
                ["2_sharded16_b64_nio", "3a_sharded16_b64_prealloc_nio", "3b_sharded16_b64_prealloc_direct"],
                key=lambda k: results[k]["bench"]["SET"]["rps"],
            )
            print(f"\n=== FULL A1 MATRIX ON WINNER: {best_label} ===", flush=True)
            win_cfg_map = {
                "2_sharded16_b64_nio": ("EVERY_SEC", 16, "nio", False, 64),
                "3a_sharded16_b64_prealloc_nio": ("EVERY_SEC", 16, "nio", True, 64),
                "3b_sharded16_b64_prealloc_direct": ("EVERY_SEC", 16, "direct", True, 64),
            }
            fsync, shards, io_mode, prealloc, batch_max = win_cfg_map[best_label]
            cdir = tmp_path / "full_matrix"
            write_cfg(cdir, fsync, shards, io_mode, prealloc, batch_max)
            start_tk(name, cdir, True)
            run_bench(name, 16, 8, 20000, "set")
            full_matrix = {}
            for cname, c, p, n in [
                ("c1_P1", 1, 1, 50000),
                ("c50_P1", 50, 1, 100000),
                ("c200_P1", 200, 1, 100000),
                ("c50_P16", 50, 16, 300000),
            ]:
                b = run_bench(name, c, p, n, "set,get")
                full_matrix[cname] = b
                print(
                    f"  {cname}: SET={b['SET']['rps']:,.0f} rps (p50={b['SET']['p50_ms']:.3f}ms p99={b['SET']['p99_ms']:.3f}ms) "
                    f"| GET={b['GET']['rps']:,.0f} rps (p50={b['GET']['p50_ms']:.3f}ms p99={b['GET']['p99_ms']:.3f}ms)",
                    flush=True,
                )
            results["winner_full_matrix"] = {"winner": best_label, "matrix": full_matrix, "wal_metrics": fetch_wal_metrics(name)}

        out_path = Path(__file__).resolve().parent / "v650_wal_ablation_results.json"
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"\nSaved results to {out_path}", flush=True)
    finally:
        cleanup_container(name)
        cleanup_volume(VOL_NAME)
        subprocess.run(["docker", "network", "rm", NETWORK], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


if __name__ == "__main__":
    main()
