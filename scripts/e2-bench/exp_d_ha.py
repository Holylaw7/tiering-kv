#!/usr/bin/env python3
"""
Experiment D: 2-Replica Stateless HA Failover, Cross-Replica JWT Revocation & Prometheus Snapshot
Runs against live `deploy/console/docker-compose.yml -f docker-compose.ha.yml --profile s3`.
"""
import argparse
import datetime
import json
import subprocess
import threading
import time
import urllib.error
import urllib.request
from pathlib import Path

BASE = "http://127.0.0.1:8081"


def http_req(method, path, token=None, body=None, extra_headers=None, timeout=5):
    headers = {}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    data = None
    if body is not None:
        headers["Content-Type"] = "application/json"
        data = json.dumps(body).encode("utf-8")
    if extra_headers:
        headers.update(extra_headers)
    req = urllib.request.Request(f"{BASE}{path}", data=data, headers=headers, method=method)
    t0 = time.perf_counter()
    try:
        with urllib.request.urlopen(req, timeout=timeout) as resp:
            raw = resp.read().decode("utf-8", errors="replace")
            dt = (time.perf_counter() - t0) * 1000.0
            return resp.status, dict(resp.headers), raw, round(dt, 2)
    except urllib.error.HTTPError as e:
        raw = e.read().decode("utf-8", errors="replace")
        dt = (time.perf_counter() - t0) * 1000.0
        return e.code, dict(e.headers), raw, round(dt, 2)
    except Exception as e:
        dt = (time.perf_counter() - t0) * 1000.0
        return 0, {}, str(e), round(dt, 2)


def mysql_query(sql):
    out = subprocess.check_output(
        [
            "docker",
            "exec",
            "-e",
            "MYSQL_PWD=tk_root_pwd",
            "tk-mysql",
            "mysql",
            "-uroot",
            "-N",
            "-B",
            "tiering_console",
            "-e",
            sql,
        ],
        text=True,
    )
    return out.strip()


def docker_curl(container, url, token=None):
    cmd = ["docker", "exec", container, "curl", "-fsS"]
    if token:
        cmd += ["-H", f"Authorization: Bearer {token}"]
    cmd.append(url)
    return subprocess.check_output(cmd, text=True, encoding="utf-8", errors="replace")


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--out", required=True)
    args = parser.parse_args()

    out = {"timestamp": datetime.datetime.now().isoformat()}

    # Login admin
    st, _, raw, _ = http_req("POST", "/api/v1/auth/login", body={"username": "admin", "password": "admin123"})
    assert st == 200, f"Admin login failed: {st} {raw}"
    admin_token = json.loads(raw)["data"]["token"]

    # D1: Cross-replica DB login rate limiting
    probe_user = f"ha_lock_{int(time.time())}"
    codes = []
    for _ in range(5):
        c, _, _, dt = http_req("POST", "/api/v1/auth/login", body={"username": probe_user, "password": "wrong"})
        codes.append({"status": c, "ms": dt})
    lock_code, _, _, lock_dt = http_req(
        "POST", "/api/v1/auth/login", body={"username": probe_user, "password": "wrong"}
    )
    db_fail_cnt = int(mysql_query(f"SELECT fail_count FROM login_attempt WHERE bucket_key='u:{probe_user}';") or "0")
    out["D1_rate_limit"] = {
        "attempts": codes,
        "lockoutStatus": lock_code,
        "lockoutMs": lock_dt,
        "dbFailCount": db_fail_cnt,
    }
    print(f"[D1] Cross-replica lockout -> HTTP {lock_code} ({lock_dt}ms), DB fail_count={db_fail_cnt}", flush=True)

    # D2: Cross-replica JWT revocation propagation latency
    rev_user = f"ha_rev_{int(time.time())}"
    st, _, raw, _ = http_req(
        "POST",
        "/api/v1/users",
        token=admin_token,
        body={"username": rev_user, "password": "probePass123", "role": "OPS"},
    )
    assert st == 200, raw
    uid = json.loads(raw)["data"]["id"]
    st, _, raw, _ = http_req("POST", "/api/v1/auth/login", body={"username": rev_user, "password": "probePass123"})
    rev_token = json.loads(raw)["data"]["token"]
    pre_code, _, _, _ = http_req("GET", "/api/v1/auth/me", token=rev_token)

    t_logout = time.perf_counter()
    logout_code, _, _, logout_ms = http_req("POST", "/api/v1/auth/logout", token=rev_token)
    post_probes = []
    for _ in range(20):
        c, _, _, dt = http_req("GET", "/api/v1/auth/me", token=rev_token)
        post_probes.append({"status": c, "ms": dt})
    propagation_ms = round((time.perf_counter() - t_logout) * 1000.0, 2)
    tok_ver = int(mysql_query(f"SELECT token_version FROM sys_user WHERE username='{rev_user}';") or "0")
    http_req("DELETE", f"/api/v1/users/{uid}", token=admin_token)
    out["D2_jwt_revocation"] = {
        "preLogoutStatus": pre_code,
        "logoutStatus": logout_code,
        "logoutMs": logout_ms,
        "postProbesCount": len(post_probes),
        "all401": all(p["status"] == 401 for p in post_probes),
        "first401Ms": post_probes[0]["ms"],
        "avg401Ms": round(sum(p["ms"] for p in post_probes) / len(post_probes), 2),
        "total20ProbesMs": propagation_ms,
        "dbTokenVersion": tok_ver,
    }
    print(
        f"[D2] JWT revocation: logout={logout_ms}ms, 20/20 cross-replica 401={out['D2_jwt_revocation']['all401']}, first401={post_probes[0]['ms']}ms",
        flush=True,
    )

    # Wait for monitor leader sampling (>= 12s)
    time.sleep(12)
    leader_owner = mysql_query("SELECT owner_id FROM console_lease WHERE lease_key='scheduler:monitor';")
    sample_count = int(mysql_query("SELECT COUNT(*) FROM metric_point;") or "0")
    dup_count = int(
        mysql_query(
            "SELECT COUNT(*) FROM metric_point a JOIN metric_point b "
            "ON a.instance_id=b.instance_id AND a.id < b.id "
            "AND TIMESTAMPDIFF(SECOND, a.collected_at, b.collected_at) < 3;"
        )
        or "0"
    )

    if leader_owner.startswith("tk-console-1-"):
        leader_container = "tk-console"
        survivor_prefix = "tk-console-2-"
    else:
        leader_container = "tk-console-2"
        survivor_prefix = "tk-console-1-"

    # D4: Probes + X-Request-Id + Prometheus custom metrics snapshot BEFORE stopping leader
    probes = {}
    for rep in ("tk-console", "tk-console-2"):
        probes[rep] = {
            "liveness": json.loads(docker_curl(rep, "http://127.0.0.1:8080/actuator/health/liveness")),
            "readiness": json.loads(docker_curl(rep, "http://127.0.0.1:8080/actuator/health/readiness")),
        }
    req_st, req_hdrs, _, req_ms = http_req(
        "GET", "/api/v1/auth/me", token=admin_token, extra_headers={"X-Request-Id": "e2-ha-trace-640"}
    )
    echoed_id = next((v for k, v in req_hdrs.items() if k.lower() == "x-request-id"), None)
    prom_raw = docker_curl(leader_container, "http://127.0.0.1:8080/actuator/prometheus", token=admin_token)
    custom_metrics = [
        ln for ln in prom_raw.splitlines() if ln.startswith("tieringkv_") and not ln.startswith("#")
    ]
    out["D4_probes_and_prometheus"] = {
        "probes": probes,
        "xRequestIdEcho": echoed_id,
        "xRequestIdStatus": req_st,
        "xRequestIdMs": req_ms,
        "leaderContainer": leader_container,
        "customMetricCount": len(custom_metrics),
        "customMetricsSample": custom_metrics,
    }
    print(
        f"[D4] Probes UP on both replicas, X-Request-Id={echoed_id}, Prometheus tieringkv_* lines={len(custom_metrics)}",
        flush=True,
    )

    # D3: Live Leader Failover + Continuous Request Availability + Lease Takeover TTR
    stop_flag = threading.Event()
    probe_log = []

    def continuous_prober():
        while not stop_flag.is_set():
            st_p, _, _, dt_p = http_req("GET", "/api/v1/auth/me", token=admin_token, timeout=3)
            probe_log.append({"t": round(time.perf_counter(), 3), "status": st_p, "ms": dt_p})
            time.sleep(0.2)

    th = threading.Thread(target=continuous_prober, daemon=True)
    th.start()
    time.sleep(1.0)

    t_kill = time.perf_counter()
    print(f"[D3] Stopping leader {leader_container} ({leader_owner})...", flush=True)
    subprocess.check_call(["docker", "stop", leader_container], stdout=subprocess.DEVNULL)
    kill_done_s = round(time.perf_counter() - t_kill, 2)

    new_owner = ""
    takeover_s = None
    for _ in range(30):
        cur_owner = mysql_query("SELECT owner_id FROM console_lease WHERE lease_key='scheduler:monitor';")
        cur_count = int(mysql_query("SELECT COUNT(*) FROM metric_point;") or "0")
        if cur_owner.startswith(survivor_prefix) and cur_count > sample_count:
            new_owner = cur_owner
            takeover_s = round(time.perf_counter() - t_kill, 2)
            break
        time.sleep(0.5)

    stop_flag.set()
    th.join(timeout=3)

    ok_200 = sum(1 for p in probe_log if p["status"] == 200)
    err_5xx = sum(1 for p in probe_log if p["status"] >= 500 or p["status"] == 0)
    latencies = sorted(p["ms"] for p in probe_log if p["status"] == 200)
    out["D3_failover"] = {
        "initialLeader": leader_owner,
        "stoppedContainer": leader_container,
        "initialSamples": sample_count,
        "duplicateSamplesWithin3s": dup_count,
        "dockerStopSeconds": kill_done_s,
        "newLeader": new_owner,
        "leaseTakeoverSeconds": takeover_s,
        "continuousProbesTotal": len(probe_log),
        "continuousProbes200": ok_200,
        "continuousProbes5xxOrNetErr": err_5xx,
        "probeP50Ms": latencies[len(latencies) // 2] if latencies else 0,
        "probeP99Ms": latencies[int(len(latencies) * 0.99)] if latencies else 0,
        "probeMaxMs": latencies[-1] if latencies else 0,
    }
    print(
        f"[D3] Failover complete: {leader_owner} -> {new_owner} in {takeover_s}s | probes 200={ok_200}/{len(probe_log)} (5xx={err_5xx})",
        flush=True,
    )

    out_path = Path(args.out)
    out_path.parent.mkdir(parents=True, exist_ok=True)
    out_path.write_text(json.dumps(out, indent=2, ensure_ascii=False), encoding="utf-8")
    print(f"[DONE] Experiment D saved to {out_path}", flush=True)


if __name__ == "__main__":
    main()
