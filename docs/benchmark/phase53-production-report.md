# Phase 53 Production Report

## 口径

LOCAL 进程内口径；跨机项保持封板。

## 基准摘要

| 路径 | 结果 | 口径 |
| --- | --- | --- |
| MULTI/EXEC | 111–333K ops/s | LOCAL |
| HSCAN | 100–156K ops/s | LOCAL |
| LMOVE | 333K–1.67M ops/s | LOCAL |
| ZRANGEBYLEX | 28–62K ops/s | LOCAL |

详细输出见 `Phase53BenchmarkTest`（PHASE53-BENCH-*）。
