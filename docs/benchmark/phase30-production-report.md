# Phase 30 生产基准报告

Phase 30 · 2026-08-11 · 进程内口径（跨地域待 CI/裸机）

```text
PHASE30-BENCH ROUTE 1M-10M ops/s
PHASE30-BENCH MIGRATE 1M-5M ops/s
PHASE30-BENCH SQL-TXN 6.25K-143K txn/s
PHASE30-BENCH INVOICE 5.9K-143K ops/s
PHASE30-BENCH CAPACITY 1ms（10K 次）
```

说明：动态重分片/向量迁移/SQL 写事务为进程内等价；跨地域
RTT/RTO/RPO 与真实 Runner 执行由 CI 补充。
