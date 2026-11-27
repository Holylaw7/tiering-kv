#!/usr/bin/env python3
"""
Experiment A: Tiering-KV v6.4.0 vs Redis 7 Container Benchmark (i9-13900H / WSL2 Docker)
Reproduces the exact tkbench container network protocol from docs/benchmark/tiering-vs-redis7.md
and adds:
  - A1: Throughput & latency (c=1, c=50, c=200, c=50 P=16) for v6.4.0 (WAL EVERY_SEC),
        v6.4.0 (WAL NO aligned with Redis AOF off), and redis:7-alpine
  - A2: 630,000 keys x 64B exact memory & container RSS comparison (-Xmx2g & -Xmx1g vs Redis 7)
  - A3: Cold-tier end-to-end verification (hit rate 100%, SSTable disk footprint, hot vs cold latency)
  - A4: Linux Native Epoll vs NIO transport A/B verification in the exact same container image
"""
import argparse
import datetime
import json
import os
import re
import shutil
import socket
import subprocess
import sys
import tempfile
import time
from pathlib import Path

REPO_ROOT = Path(__file__).resolve().parents[2]
TK_IMAGE = "tiering-kv-console-tiering-kv:latest"
REDIS_IMAGE = "redis:7-alpine"
NETWORK = "tkbench-e2"


def run(cmd, check=True, capture=True, timeout=600):
    res = subprocess.run(
        cmd,
        stdout=subprocess.PIPE if capture else None,
        stderr=subprocess.STDOUT if capture else None,
        text=True,
        encoding="utf-8",
        errors="replace",
        timeout=timeout,
    )
    if check and res.returncode != 0:
        raise RuntimeError(f"Command failed ({res.returncode}): {' '.join(cmd)}\n{res.stdout}")
    return res.stdout or ""


def rm_container(name):
    subprocess.run(["docker", "rm", "-f", name], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)


def ensure_network():
    subprocess.run(["docker", "network", "rm", NETWORK], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
    run(["docker", "network", "create", NETWORK])


def wait_tk_ready(name, timeout_s=40):
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
        time.sleep(0.5)
    logs = run(["docker", "logs", "--tail", "40", name], check=False)
    raise RuntimeError(f"Container {name} did not become ready in {timeout_s}s:\n{logs}")


def wait_redis_ready(name, timeout_s=20):
    deadline = time.time() + timeout_s
    while time.time() < deadline:
        r = subprocess.run(
            ["docker", "exec", name, "redis-cli", "PING"],
            stdout=subprocess.PIPE,
            stderr=subprocess.DEVNULL,
            text=True,
        )
        if r.returncode == 0 and "PONG" in r.stdout:
            return
        time.sleep(0.3)
    raise RuntimeError(f"Redis {name} did not become ready")


def start_tk(name, config_dir, xmx="2g", extra_java_opts="", publish_port=None):
    rm_container(name)
    java_opts = (
        f"-XX:+UseZGC -XX:+ZGenerational -Dio.netty.allocator.type=pooled -Xmx{xmx} {extra_java_opts}".strip()
    )
    cmd = [
        "docker",
        "run",
        "-d",
        "--name",
        name,
        "--network",
        NETWORK,
        "-v",
        f"{str(config_dir)}:/app/config:ro",
        "-w",
        "/app",
    ]
    if publish_port:
        cmd += ["-p", f"127.0.0.1:{publish_port}:6379"]
    cmd += [
        "--entrypoint",
        "sh",
        TK_IMAGE,
        "-c",
        f"exec java {java_opts} -cp /app/tiering-kv.jar io.tieringkv.Main",
    ]
    run(cmd)
    wait_tk_ready(name)
    logs = run(["docker", "logs", name], check=False)
    transport = "UNKNOWN"
    for line in logs.splitlines():
        if "Epoll" in line or "EPOLL" in line:
            transport = "EPOLL"
        elif "NIO" in line or "NioEventLoop" in line:
            if transport == "UNKNOWN":
                transport = "NIO"
    return {"javaOpts": java_opts, "transportLog": transport, "startupTail": logs.splitlines()[:15]}


def start_redis(name, publish_port=None):
    rm_container(name)
    cmd = ["docker", "run", "-d", "--name", name, "--network", NETWORK]
    if publish_port:
        cmd += ["-p", f"127.0.0.1:{publish_port}:6379"]
    cmd += [REDIS_IMAGE, "redis-server", "--save", "", "--appendonly", "no"]
    run(cmd)
    wait_redis_ready(name)


def flushall_in_net(host):
    run(["docker", "run", "--rm", "--network", NETWORK, REDIS_IMAGE, "redis-cli", "-h", host, "-p", "6379", "FLUSHALL"], check=False)


def run_redis_benchmark(host, clients, pipeline, requests=200000, keyspace=1000000, datasize=64):
    flushall_in_net(host)
    cmd = [
        "docker",
        "run",
        "--rm",
        "--network",
        NETWORK,
        REDIS_IMAGE,
        "redis-benchmark",
        "-h",
        host,
        "-p",
        "6379",
        "-n",
        str(requests),
        "-c",
        str(clients),
        "-P",
        str(pipeline),
        "-r",
        str(keyspace),
        "-d",
        str(datasize),
        "-t",
        "set,get",
    ]
    out = run(cmd, timeout=300)
    parsed = {}
    current = None
    for line in out.splitlines():
        s = line.strip()
        if s.startswith("====== SET"):
            current = "SET"
            parsed[current] = {}
        elif s.startswith("====== GET"):
            current = "GET"
            parsed[current] = {}
        elif current:
            m_rps = re.search(r"throughput summary:\s+([0-9.]+)\s+requests per second", s)
            if m_rps:
                parsed[current]["rps"] = float(m_rps.group(1))
            m_lat = re.match(r"([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)\s+([0-9.]+)$", s)
            if m_lat:
                # avg min p50 p95 p99 max
                parsed[current]["avg_ms"] = float(m_lat.group(1))
                parsed[current]["min_ms"] = float(m_lat.group(2))
                parsed[current]["p50_ms"] = float(m_lat.group(3))
                parsed[current]["p95_ms"] = float(m_lat.group(4))
                parsed[current]["p99_ms"] = float(m_lat.group(5))
                parsed[current]["max_ms"] = float(m_lat.group(6))
    return parsed


def docker_stats_rss(name):
    out = run(["docker", "stats", "--no-stream", "--format", "{{.MemUsage}}|{{.CPUPerc}}", name]).strip()
    mem_part = out.split("|")[0].strip()
    used_str = mem_part.split("/")[0].strip()
    m = re.match(r"([0-9.]+)\s*([A-Za-z]+)", used_str)
    mib = 0.0
    if m:
        val = float(m.group(1))
        unit = m.group(2).lower()
        if unit.startswith("g"):
            mib = val * 1024.0
        elif unit.startswith("m"):
            mib = val
        elif unit.startswith("k"):
            mib = val / 1024.0
    return {"raw": mem_part, "usedStr": used_str, "rssMiB": round(mib, 2)}


def parse_info(host_port, section=None):
    with socket.create_connection(("127.0.0.1", host_port), timeout=10) as s:
        if section:
            cmd = f"*2\r\n$4\r\nINFO\r\n${len(section)}\r\n{section}\r\n"
        else:
            cmd = "*1\r\n$4\r\nINFO\r\n"
        s.sendall(cmd.encode())
        buf = b""
        s.settimeout(5)
        while True:
            chunk = s.recv(65536)
            if not chunk:
                break
            buf += chunk
            if buf.startswith(b"$"):
                idx = buf.find(b"\r\n")
                if idx > 0:
                    length = int(buf[1:idx])
                    if len(buf) >= idx + 2 + length + 2:
                        text = buf[idx + 2 : idx + 2 + length].decode("utf-8", errors="replace")
                        kv = {}
                        for ln in text.splitlines():
                            if ":" in ln and not ln.startswith("#"):
                                k, v = ln.split(":", 1)
                                kv[k.strip()] = v.strip()
                        return kv
    return {}


def seed_keys_pipeline(port, count, value_len=64, batch_size=500):
    val = "v" * value_len
    t0 = time.perf_counter()
    with socket.create_connection(("127.0.0.1", port), timeout=15) as s:
        s.setsockopt(socket.IPPROTO_TCP, socket.TCP_NODELAY, 1)
        idx = 0
        while idx < count:
            end = min(count, idx + batch_size)
            chunks = []
            for i in range(idx, end):
                k = f"k:{i:07d}"
                chunks.append(f"*3\r\n$3\r\nSET\r\n${len(k)}\r\n{k}\r\n${value_len}\r\n{val}\r\n")
            s.sendall("".join(chunks).encode("ascii"))
            # Read exact number of simple-string +OK responses
            expected = end - idx
            got = 0
            buf = b""
            while got < expected:
                data = s.recv(65536)
                if not data:
                    raise RuntimeError("Socket closed during seed")
                buf += data
                while True:
                    nl = buf.find(b"\r\n")
                    if nl < 0:
                        break
                    line = buf[:nl]
                    buf = buf[nl + 2 :]
                    if line.startswith(b"-"):
                        raise RuntimeError(f"RESP error during seed at {idx}: {line.decode()}")
                    got += 1
            idx = end
    return round(time.perf_counter() - t0, 2)


def make_config(tmp_root, name, overrides):
    dst = tmp_root / name
    dst.mkdir(parents=True, exist_ok=True)
    base = (REPO_ROOT / "config" / "application.yaml").read_text(encoding="utf-8")
    for old, new in overrides:
        assert old in base, f"Override pattern not found: {old}"
        base = base.replace(old, new, 1)
    (dst / "application.yaml").write_text(base, encoding="utf-8")
    return dst


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)

    docker_info = run(
        ["docker", "info", "--format", "{{.ServerVersion}}|{{.NCPU}}|{{.MemTotal}}|{{.KernelVersion}}"]
    ).strip()
    results = {
        "timestamp": datetime.datetime.now().isoformat(),
        "dockerInfo": docker_info,
        "tkImage": TK_IMAGE,
        "redisImage": REDIS_IMAGE,
        "A1_matrix": {},
        "A2_memory_630k": {},
        "A3_tiering": {},
        "A4_epoll_vs_nio": {},
    }

    tmp_root = Path(tempfile.mkdtemp(prefix="tk-e2-expa-"))
    try:
        ensure_network()
        cfg_default = make_config(tmp_root, "cfg-default", [])
        cfg_wal_no = make_config(tmp_root, "cfg-wal-no", [("fsync-policy: EVERY_SEC", 'fsync-policy: "NO"')])
        cfg_tiering = make_config(
            tmp_root,
            "cfg-tiering",
            [
                ("limit: 1073741824", "limit: 134217728"),  # 128 MB hot tier
            ],
        )

        # ---- A1.1: Tiering-KV v6.4.0 (default WAL EVERY_SEC, Epoll, Generational ZGC -Xmx2g) ----
        print("[A1] Starting Tiering-KV v6.4.0 (WAL EVERY_SEC, Epoll, ZGC -Xmx2g)...", flush=True)
        tk_meta = start_tk("bench-tk-default", cfg_default, xmx="2g")
        # Warmup JIT + Epoll
        run_redis_benchmark("bench-tk-default", clients=50, pipeline=1, requests=50000)
        a1_tk_default = {"meta": tk_meta}
        for label, c, p in [("c1_p1", 1, 1), ("c50_p1", 50, 1), ("c200_p1", 200, 1), ("c50_p16", 50, 16)]:
            print(f"  -> v6.4.0 (WAL EVERY_SEC) {label}", flush=True)
            a1_tk_default[label] = run_redis_benchmark("bench-tk-default", clients=c, pipeline=p)
            print(f"     SET={a1_tk_default[label].get('SET',{}).get('rps')} rps | GET={a1_tk_default[label].get('GET',{}).get('rps')} rps", flush=True)
        results["A1_matrix"]["v640_wal_everysec"] = a1_tk_default
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        rm_container("bench-tk-default")

        # ---- A1.2: Tiering-KV v6.4.0 (WAL NO, aligned with Redis AOF off) ----
        print("[A1] Starting Tiering-KV v6.4.0 (WAL NO, Epoll, ZGC -Xmx2g)...", flush=True)
        tk_walno_meta = start_tk("bench-tk-walno", cfg_wal_no, xmx="2g")
        run_redis_benchmark("bench-tk-walno", clients=50, pipeline=1, requests=50000)
        a1_tk_walno = {"meta": tk_walno_meta}
        for label, c, p in [("c1_p1", 1, 1), ("c50_p1", 50, 1), ("c200_p1", 200, 1), ("c50_p16", 50, 16)]:
            print(f"  -> v6.4.0 (WAL NO) {label}", flush=True)
            a1_tk_walno[label] = run_redis_benchmark("bench-tk-walno", clients=c, pipeline=p)
            print(f"     SET={a1_tk_walno[label].get('SET',{}).get('rps')} rps | GET={a1_tk_walno[label].get('GET',{}).get('rps')} rps", flush=True)
        results["A1_matrix"]["v640_wal_no"] = a1_tk_walno
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        rm_container("bench-tk-walno")

        # ---- A1.3: Redis 7 Alpine (--save '' --appendonly no) ----
        print("[A1] Starting Redis 7 Alpine...", flush=True)
        start_redis("bench-redis")
        run_redis_benchmark("bench-redis", clients=50, pipeline=1, requests=50000)
        a1_redis = {}
        for label, c, p in [("c1_p1", 1, 1), ("c50_p1", 50, 1), ("c200_p1", 200, 1), ("c50_p16", 50, 16)]:
            print(f"  -> redis:7-alpine {label}", flush=True)
            a1_redis[label] = run_redis_benchmark("bench-redis", clients=c, pipeline=p)
            print(f"     SET={a1_redis[label].get('SET',{}).get('rps')} rps | GET={a1_redis[label].get('GET',{}).get('rps')} rps", flush=True)
        results["A1_matrix"]["redis7"] = a1_redis
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        rm_container("bench-redis")

        # ---- A4: Transport A/B (NIO fallback vs Epoll from A1.1) ----
        print("[A4] Starting Tiering-KV v6.4.0 with -Dtieringkv.transport=nio...", flush=True)
        tk_nio_meta = start_tk("bench-tk-nio", cfg_default, xmx="2g", extra_java_opts="-Dtieringkv.transport=nio")
        run_redis_benchmark("bench-tk-nio", clients=50, pipeline=1, requests=50000)
        a4_nio = {"meta": tk_nio_meta}
        for label, c, p in [("c50_p1", 50, 1), ("c50_p16", 50, 16)]:
            print(f"  -> v6.4.0 (NIO) {label}", flush=True)
            a4_nio[label] = run_redis_benchmark("bench-tk-nio", clients=c, pipeline=p)
            print(f"     SET={a4_nio[label].get('SET',{}).get('rps')} rps | GET={a4_nio[label].get('GET',{}).get('rps')} rps", flush=True)
        results["A4_epoll_vs_nio"] = {
            "epoll": {
                "c50_p1": a1_tk_default["c50_p1"],
                "c50_p16": a1_tk_default["c50_p16"],
            },
            "nio": a4_nio,
        }
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        rm_container("bench-tk-nio")

        # ---- A2: 630,000 keys x 64B Memory & Container RSS ----
        a2_profiles = [
            ("v640_xmx2g", "2g", ""),
            ("v640_zgc_tuned", "2g", "-XX:SoftMaxHeapSize=512m -XX:ZUncommitDelay=3"),
        ]
        for mode_label, xmx, extra_opts in a2_profiles:
            print(f"[A2] Measuring 630K x 64B memory & RSS ({mode_label})...", flush=True)
            start_tk("bench-mem-tk", cfg_default, xmx=xmx, extra_java_opts=extra_opts, publish_port=16379)
            seed_s = seed_keys_pipeline(16379, 630000, value_len=64)
            time.sleep(6)
            info_mem = parse_info(16379, "memory")
            info_ks = parse_info(16379, "keyspace")
            rss = docker_stats_rss("bench-mem-tk")
            results["A2_memory_630k"][mode_label] = {
                "seedSeconds": seed_s,
                "used_memory": info_mem.get("used_memory"),
                "used_memory_human": info_mem.get("used_memory_human"),
                "used_memory_jvm": info_mem.get("used_memory_jvm"),
                "keyspace": info_ks,
                "dockerStats": rss,
            }
            print(f"  -> {mode_label}: used_memory={info_mem.get('used_memory_human')}, jvm={info_mem.get('used_memory_jvm')}, RSS={rss['usedStr']}", flush=True)
            rm_container("bench-mem-tk")

        print("[A2] Measuring 630K x 64B memory & RSS (redis7)...", flush=True)
        start_redis("bench-mem-redis", publish_port=16379)
        seed_s = seed_keys_pipeline(16379, 630000, value_len=64)
        time.sleep(2)
        info_mem = parse_info(16379, "memory")
        info_ks = parse_info(16379, "keyspace")
        rss = docker_stats_rss("bench-mem-redis")
        results["A2_memory_630k"]["redis7"] = {
            "seedSeconds": seed_s,
            "used_memory": info_mem.get("used_memory"),
            "used_memory_human": info_mem.get("used_memory_human"),
            "keyspace": info_ks,
            "dockerStats": rss,
        }
        print(f"  -> redis7: used_memory={info_mem.get('used_memory_human')}, RSS={rss['usedStr']}", flush=True)
        rm_container("bench-mem-redis")
        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")

        # ---- A3: Cold-Tier End-to-End Verification (128MB hot limit, 350K x 256B -> real cold migration) ----
        print("[A3] Running cold-tier end-to-end hit-rate & latency test (128MB hot limit, 350K x 256B)...", flush=True)
        start_tk("bench-tiering", cfg_tiering, xmx="1536m", publish_port=16379)
        tier_json = tmp_root / "tiering.json"
        tier_out = run(
            [
                "node",
                str(REPO_ROOT / "scripts" / "verify-tiering.mjs"),
                "--host",
                "127.0.0.1",
                "--port",
                "16379",
                "--keys",
                "350000",
                "--value",
                "256",
                "--json",
                str(tier_json),
            ],
            timeout=300,
        )
        cold_ls = run(
            ["docker", "exec", "bench-tiering", "sh", "-c", "du -sb /app/data/cold /app/data/wal 2>/dev/null; ls -1 /app/data/cold 2>/dev/null | wc -l"],
            check=False,
        )
        info_tier = parse_info(16379, "tiering")
        rss_tier = docker_stats_rss("bench-tiering")
        tier_parsed = json.loads(tier_json.read_text(encoding="utf-8")) if tier_json.exists() else {}
        results["A3_tiering"] = {
            "verifyOutputTail": tier_out.splitlines()[-25:],
            "verifyJson": tier_parsed,
            "coldDiskProbe": cold_ls.strip(),
            "infoTiering": info_tier,
            "dockerStats": rss_tier,
        }
        rm_container("bench-tiering")

        out_path.write_text(json.dumps(results, indent=2, ensure_ascii=False), encoding="utf-8")
        print(f"[DONE] Experiment A saved to {out_path}", flush=True)
    finally:
        for c in ("bench-tk-default", "bench-tk-walno", "bench-redis", "bench-tk-nio", "bench-mem-tk", "bench-mem-redis", "bench-tiering"):
            rm_container(c)
        subprocess.run(["docker", "network", "rm", NETWORK], stdout=subprocess.DEVNULL, stderr=subprocess.DEVNULL)
        shutil.rmtree(tmp_root, ignore_errors=True)


if __name__ == "__main__":
    main()
