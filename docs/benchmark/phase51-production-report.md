# Phase 51 Production Report

## 口径

LOCAL 进程内口径；跨机/跨地域项保持封板（ENV_BLOCKED_FINAL）。

## 基准摘要

| 路径 | 结果 | 口径 |
| --- | --- | --- |
| INCR | 0.2–0.9M ops/s | LOCAL |
| MSET | 0.1–0.24M ops/s | LOCAL |
| SCAN | 43K–1M keys/s | LOCAL |
| TTL | 0.33–2M ops/s | LOCAL |

详细输出见 `Phase51BenchmarkTest`（PHASE51-BENCH-*）。
