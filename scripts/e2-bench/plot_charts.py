#!/usr/bin/env python3
"""
Generate 4 publication-grade 300 DPI Chinese-font Matplotlib charts into docs/design/figures/
directly from raw E2 JSON measurements:
  1. fig6-0-tiering-vs-redis7-v640.png  (v5.0.0 vs v6.4.0 vs Redis 7 throughput & 630K RSS)
  2. fig6-1-api-latency.png            (20 core REST APIs P50/P95/P99 latency)
  3. fig6-2-backup-scale.png           (Local Gzip vs S3/RustFS backup/verify/preview/restore)
  4. fig6-3-concurrency.png            (c=10/20/50 bounded RESP pool throughput & latency)
"""
import argparse
import json
from pathlib import Path

import matplotlib
matplotlib.use("Agg")
import matplotlib.pyplot as plt
import numpy as np

plt.rcParams["font.sans-serif"] = ["Microsoft YaHei", "SimHei", "Noto Sans SC", "DejaVu Sans"]
plt.rcParams["axes.unicode_minus"] = False
plt.rcParams["axes.edgecolor"] = "#CBD5E1"
plt.rcParams["axes.linewidth"] = 0.8

COLORS = {
    "v500": "#94A3B8",
    "v640": "#2563EB",
    "v640_no": "#0EA5E9",
    "redis": "#DC2626",
    "green": "#16A34A",
    "amber": "#D97706",
    "violet": "#7C3AED",
    "slate": "#475569",
}


def plot_fig6_0(exp_a, out_dir):
    # v5.0.0 baseline from docs/benchmark/tiering-vs-redis7.md (same host i9-13900H / Docker WSL2)
    v500_get = [11.15, 63.51, 55.30, 227.27]
    v500_set = [10.35, 59.70, 53.06, 161.29]

    m_def = exp_a["A1_matrix"]["v640_wal_everysec"]
    m_no = exp_a["A1_matrix"]["v640_wal_no"]
    m_red = exp_a["A1_matrix"]["redis7"]
    keys = ["c1_p1", "c50_p1", "c200_p1", "c50_p16"]
    labels = ["c=1, P=1", "c=50, P=1", "c=200, P=1", "c=50, P=16"]

    v640_get = [m_def[k]["GET"]["rps"] / 1000.0 for k in keys]
    red_get = [m_red[k]["GET"]["rps"] / 1000.0 for k in keys]
    v640_set = [m_def[k]["SET"]["rps"] / 1000.0 for k in keys]
    v640_no_set = [m_no[k]["SET"]["rps"] / 1000.0 for k in keys]
    red_set = [m_red[k]["SET"]["rps"] / 1000.0 for k in keys]

    mem = exp_a["A2_memory_630k"]
    rss_labels = ["v5.0.0\n(-Xmx2g)", "v6.4.0\n(-Xmx2g)", "v6.4.0\n(ZGC SoftMax)", "Redis 7\n(C 原生)"]
    rss_vals = [
        1377.28,  # 1.345 GiB from docs/benchmark/tiering-vs-redis7.md
        mem["v640_xmx2g"]["dockerStats"]["rssMiB"],
        mem["v640_zgc_tuned"]["dockerStats"]["rssMiB"],
        mem["redis7"]["dockerStats"]["rssMiB"],
    ]

    fig, (ax1, ax2, ax3) = plt.subplots(1, 3, figsize=(15.5, 4.8), gridspec_kw={"width_ratios": [1.25, 1.25, 1.0]})
    x = np.arange(len(labels))
    w = 0.25

    # Panel 1: GET Throughput
    b1 = ax1.bar(x - w, v500_get, w, label="Tiering-KV v5.0.0", color=COLORS["v500"])
    b2 = ax1.bar(x, v640_get, w, label="Tiering-KV v6.4.0 (Epoll+ZGC)", color=COLORS["v640"])
    b3 = ax1.bar(x + w, red_get, w, label="Redis 7 Alpine", color=COLORS["redis"], alpha=0.88)
    ax1.set_title("(a) 容器网络 GET 吞吐演进（k ops/s）", fontsize=11, fontweight="bold", pad=10)
    ax1.set_xticks(x)
    ax1.set_xticklabels(labels, fontsize=9)
    ax1.set_ylabel("吞吐量（k req/s）", fontsize=9.5)
    ax1.grid(axis="y", linestyle="--", alpha=0.35)
    ax1.legend(fontsize=8, frameon=True, loc="upper left")
    for bars in (b1, b2, b3):
        for bar in bars:
            h = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width() / 2, h + max(red_get) * 0.015, f"{h:.0f}", ha="center", va="bottom", fontsize=7.5)

    # Panel 2: SET Throughput
    w2 = 0.20
    s1 = ax2.bar(x - 1.5 * w2, v500_set, w2, label="v5.0.0 (WAL EVERY_SEC)", color=COLORS["v500"])
    s2 = ax2.bar(x - 0.5 * w2, v640_set, w2, label="v6.4.0 (WAL EVERY_SEC)", color=COLORS["v640"])
    s3 = ax2.bar(x + 0.5 * w2, v640_no_set, w2, label="v6.4.0 (WAL NO 对齐)", color=COLORS["v640_no"])
    s4 = ax2.bar(x + 1.5 * w2, red_set, w2, label="Redis 7 (AOF off)", color=COLORS["redis"], alpha=0.88)
    ax2.set_title("(b) 容器网络 SET 吞吐演进（k ops/s）", fontsize=11, fontweight="bold", pad=10)
    ax2.set_xticks(x)
    ax2.set_xticklabels(labels, fontsize=9)
    ax2.set_ylabel("吞吐量（k req/s）", fontsize=9.5)
    ax2.grid(axis="y", linestyle="--", alpha=0.35)
    ax2.legend(fontsize=7.5, frameon=True, loc="upper left")
    for bars in (s2, s4):
        for bar in bars:
            h = bar.get_height()
            ax2.text(bar.get_x() + bar.get_width() / 2, h + max(red_set) * 0.015, f"{h:.0f}", ha="center", va="bottom", fontsize=7.5)

    # Panel 3: 630K keys x 64B Container RSS
    rx = np.arange(len(rss_labels))
    bar_colors = [COLORS["v500"], COLORS["v640"], COLORS["green"], COLORS["redis"]]
    rb = ax3.bar(rx, rss_vals, 0.55, color=bar_colors)
    ax3.set_title("(c) 63 万键 × 64B 容器 RSS（MiB）", fontsize=11, fontweight="bold", pad=10)
    ax3.set_xticks(rx)
    ax3.set_xticklabels(rss_labels, fontsize=8.5)
    ax3.set_ylabel("容器常驻内存 RSS（MiB）", fontsize=9.5)
    ax3.grid(axis="y", linestyle="--", alpha=0.35)
    for bar in rb:
        h = bar.get_height()
        ax3.text(bar.get_x() + bar.get_width() / 2, h + max(rss_vals) * 0.02, f"{h:.0f}", ha="center", va="bottom", fontsize=8.5, fontweight="bold")

    fig.suptitle("Tiering-KV v5.0.0 → v6.4.0 与 Redis 7 同机容器横向实测对照", fontsize=13, fontweight="bold", y=1.02)
    plt.tight_layout()
    fig.savefig(out_dir / "fig6-0-tiering-vs-redis7-v640.png", dpi=300, bbox_inches="tight")
    plt.close(fig)


def plot_fig6_1(exp_bc, out_dir):
    eps = exp_bc["B1_apis"]["endpoints"]
    # Sort by p95 descending for clean readability
    eps_sorted = sorted(eps, key=lambda r: r["p95Ms"])
    names = [e["name"] for e in eps_sorted]
    p50 = [e["p50Ms"] for e in eps_sorted]
    p95 = [e["p95Ms"] for e in eps_sorted]
    p99 = [e["p99Ms"] for e in eps_sorted]

    fig, ax = plt.subplots(figsize=(11.5, 7.6))
    y = np.arange(len(names))
    h = 0.27
    ax.barh(y - h, p50, h, label="P50 中位延迟 (ms)", color=COLORS["v640"])
    ax.barh(y, p95, h, label="P95 延迟 (ms)", color=COLORS["amber"])
    ax.barh(y + h, p99, h, label="P99 尾延迟 (ms)", color=COLORS["redis"], alpha=0.85)
    ax.set_yticks(y)
    ax.set_yticklabels(names, fontsize=8.8)
    ax.set_xlabel("端到端 HTTP 响应耗时（ms）", fontsize=10)
    ax.set_title("控制平面 20 个核心 REST 接口响应耗时分布（v6.4.0 全栈容器实测）", fontsize=12, fontweight="bold", pad=12)
    ax.grid(axis="x", linestyle="--", alpha=0.35)
    ax.legend(loc="lower right", fontsize=9, frameon=True)
    max_v = max(p99) if p99 else 1
    for i, (v50, v95) in enumerate(zip(p50, p95)):
        ax.text(max(p99[i], v95) + max_v * 0.012, i, f"P50={v50:.1f} / P95={v95:.1f} ms", va="center", fontsize=7.8, color="#334155")
    ax.set_xlim(0, max_v * 1.28)
    plt.tight_layout()
    fig.savefig(out_dir / "fig6-1-api-latency.png", dpi=300, bbox_inches="tight")
    plt.close(fig)


def plot_fig6_2(exp_bc_local, exp_c_s3, out_dir):
    loc = exp_bc_local["C_local"]["rows"]
    s3 = exp_c_s3["C_s3"]["rows"]
    scales = [f"{r['scale']//1000}K 键" for r in loc]
    x = np.arange(len(scales))
    w = 0.28

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.5, 5.0))

    # Left: Export & Restore duration (Local vs S3)
    b1 = ax1.bar(x - 1.5 * w * 0.75, [r["exportMs"] / 1000.0 for r in loc], w * 0.75, label="Local 导出 (Gzip+SHA-256)", color=COLORS["v640"])
    b2 = ax1.bar(x - 0.5 * w * 0.75, [r["exportMs"] / 1000.0 for r in s3], w * 0.75, label="S3/RustFS 导出", color=COLORS["v640_no"])
    b3 = ax1.bar(x + 0.5 * w * 0.75, [r["restoreMs"] / 1000.0 for r in loc], w * 0.75, label="Local 恢复 (100 键 Pipeline)", color=COLORS["green"])
    b4 = ax1.bar(x + 1.5 * w * 0.75, [r["restoreMs"] / 1000.0 for r in s3], w * 0.75, label="S3/RustFS 恢复", color=COLORS["amber"])
    ax1.set_title("(a) 多规模逻辑备份导出与异步 Pipeline 恢复耗时", fontsize=11, fontweight="bold", pad=10)
    ax1.set_xticks(x)
    ax1.set_xticklabels(scales, fontsize=9.5)
    ax1.set_ylabel("耗时（秒）", fontsize=10)
    ax1.grid(axis="y", linestyle="--", alpha=0.35)
    ax1.legend(fontsize=8.3, frameon=True, loc="upper left")
    for bars in (b1, b2, b3, b4):
        for bar in bars:
            h = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width() / 2, h + 0.05, f"{h:.2f}s", ha="center", va="bottom", fontsize=7.5)

    # Right: SHA-256 Verify & Dry-Run Preview overhead (ms)
    v1 = ax2.bar(x - 1.5 * w * 0.75, [r["verifyMs"] for r in loc], w * 0.75, label="Local SHA-256 校验", color=COLORS["violet"])
    v2 = ax2.bar(x - 0.5 * w * 0.75, [r["verifyMs"] for r in s3], w * 0.75, label="S3/RustFS SHA-256 校验", color="#A78BFA")
    v3 = ax2.bar(x + 0.5 * w * 0.75, [r["previewMs"] for r in loc], w * 0.75, label="Local Dry-Run 冲突预览", color=COLORS["slate"])
    v4 = ax2.bar(x + 1.5 * w * 0.75, [r["previewMs"] for r in s3], w * 0.75, label="S3/RustFS Dry-Run 冲突预览", color="#94A3B8")
    ax2.set_title("(b) 流式完整性校验与 Dry-Run 冲突预检耗时", fontsize=11, fontweight="bold", pad=10)
    ax2.set_xticks(x)
    ax2.set_xticklabels(scales, fontsize=9.5)
    ax2.set_ylabel("耗时（ms）", fontsize=10)
    ax2.grid(axis="y", linestyle="--", alpha=0.35)
    ax2.legend(fontsize=8.3, frameon=True, loc="upper left")
    for bars in (v1, v2, v3, v4):
        for bar in bars:
            h = bar.get_height()
            ax2.text(bar.get_x() + bar.get_width() / 2, h + max(r["previewMs"] for r in s3) * 0.02, f"{int(h)}", ha="center", va="bottom", fontsize=7.5)

    fig.suptitle("Tiering-KV v6.4.0 Local 磁盘与 S3/RustFS 多规模备份治理全链路对比", fontsize=13, fontweight="bold", y=1.02)
    plt.tight_layout()
    fig.savefig(out_dir / "fig6-2-backup-scale.png", dpi=300, bbox_inches="tight")
    plt.close(fig)


def plot_fig6_3(exp_bc, out_dir):
    rows = exp_bc["B2_concurrency"]
    ov = [r for r in rows if r["target"] == "overview"]
    ks = [r for r in rows if r["target"] == "keys_scan"]
    concs = [r["concurrency"] for r in ov]
    x = np.arange(len(concs))
    w = 0.32

    fig, (ax1, ax2) = plt.subplots(1, 2, figsize=(13.2, 4.8))

    # Panel 1: Throughput
    b1 = ax1.bar(x - w / 2, [r["rps"] for r in ov], w, label="GET /dashboard/overview", color=COLORS["v640"])
    b2 = ax1.bar(x + w / 2, [r["rps"] for r in ks], w, label="GET /keys/scan (20 键)", color=COLORS["green"])
    ax1.set_title("(a) 不同并发下控制面吞吐量（req/s，连接池上限=16）", fontsize=11, fontweight="bold", pad=10)
    ax1.set_xticks(x)
    ax1.set_xticklabels([f"c={c}" for c in concs], fontsize=10)
    ax1.set_ylabel("吞吐量（req/s）", fontsize=10)
    ax1.grid(axis="y", linestyle="--", alpha=0.35)
    ax1.legend(fontsize=9, frameon=True)
    for bars in (b1, b2):
        for bar in bars:
            h = bar.get_height()
            ax1.text(bar.get_x() + bar.get_width() / 2, h + 8, f"{h:.0f}", ha="center", va="bottom", fontsize=8.5, fontweight="bold")

    # Panel 2: P50 & P99 Latency Evolution
    ax2.plot(concs, [r["p50Ms"] for r in ov], "o-", color=COLORS["v640"], linewidth=2, label="overview P50")
    ax2.plot(concs, [r["p99Ms"] for r in ov], "s--", color=COLORS["v640"], linewidth=1.8, alpha=0.75, label="overview P99")
    ax2.plot(concs, [r["p50Ms"] for r in ks], "^-", color=COLORS["green"], linewidth=2, label="keys/scan P50")
    ax2.plot(concs, [r["p99Ms"] for r in ks], "d--", color=COLORS["redis"], linewidth=1.8, alpha=0.85, label="keys/scan P99")
    ax2.axvline(16, color=COLORS["amber"], linestyle=":", linewidth=1.6, label="连接池上限 maxTotal=16")
    ax2.set_title("(b) 有界连接池饱和边界与尾延迟演进（ms）", fontsize=11, fontweight="bold", pad=10)
    ax2.set_xticks(concs)
    ax2.set_xticklabels([f"c={c}" for c in concs], fontsize=10)
    ax2.set_ylabel("响应延迟（ms）", fontsize=10)
    ax2.grid(True, linestyle="--", alpha=0.35)
    ax2.legend(fontsize=8.3, frameon=True, loc="upper left")

    fig.suptitle("控制平面有界 RESP 连接池并发压力与饱和排队特征（v6.4.0）", fontsize=13, fontweight="bold", y=1.02)
    plt.tight_layout()
    fig.savefig(out_dir / "fig6-3-concurrency.png", dpi=300, bbox_inches="tight")
    plt.close(fig)


def main():
    p = argparse.ArgumentParser()
    p.add_argument("--exp-a", required=True)
    p.add_argument("--exp-bc-local", required=True)
    p.add_argument("--exp-c-s3", required=True)
    p.add_argument("--out-dir", default="docs/design/figures")
    args = p.parse_args()

    out_dir = Path(args.out_dir)
    out_dir.mkdir(parents=True, exist_ok=True)
    exp_a = json.loads(Path(args.exp_a).read_text(encoding="utf-8"))
    exp_bc_local = json.loads(Path(args.exp_bc_local).read_text(encoding="utf-8"))
    exp_c_s3 = json.loads(Path(args.exp_c_s3).read_text(encoding="utf-8"))

    plot_fig6_0(exp_a, out_dir)
    plot_fig6_1(exp_bc_local, out_dir)
    plot_fig6_2(exp_bc_local, exp_c_s3, out_dir)
    plot_fig6_3(exp_bc_local, out_dir)
    print(f"[DONE] Generated 4 300-DPI charts in {out_dir}")


if __name__ == "__main__":
    main()
