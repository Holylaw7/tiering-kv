# Phase 52 Production Report

## 口径

LOCAL 进程内口径；跨机项保持封板。

## 基准摘要

| 路径 | 结果 | 口径 |
| --- | --- | --- |
| HSET | 67–114K ops/s | LOCAL |
| RPUSH | 8.6–33K ops/s | LOCAL |
| SADD | 77–132K ops/s | LOCAL |
| ZADD | 16–80K ops/s | LOCAL |

详细输出见 `Phase52BenchmarkTest`（PHASE52-BENCH-*）。
