# GA Final Benchmark Summary

## 口径

LOCAL 进程内口径；跨机/跨地域封板声明（SEALED_GA）。

## 汇总

| 路径 | 结果 |
| --- | --- |
| MemTable GET/SET | 4M+ ops/s |
| 服务端 pipeline64 | 465K ops/s |
| 事务 SET | 144–175K ops/s |
| MULTI/EXEC | 62–139K ops/s |
| 线性化验证 | 333K–2.5M/s |
| XREADGROUP | 26–49K ops/s |

详细见各 Phase 报告与 final-performance-whitepaper.md。
