# v1.0 最终生产基准报告

Phase 26 · 2026-08-11 · 环境：Windows 11 / Temurin 17 / JVM 进程内（如实标注）

## 1. 目标与现状

| 指标 | v1 目标 | 本地实测（进程内） | 状态 |
| --- | ---: | ---: | --- |
| Gateway GET | >1M ops/s | 719K（Phase 18 TCP）/ 2.0–6.9M（存储层） | 跨机待 Runner |
| Gateway SET | >200K ops/s | 590K（Phase 18 TCP） | ✅ 口径内 |
| Transaction | >50K txn/s | 45–83K（全链路） | 峰值达标 |
| GET P99 | <5ms | ≈0.19ms（端到端） | ✅ |
| SET P99 | <20ms | 见 Phase 10 报告 | ✅ 口径内 |
| Leader failover | <500ms | 110–303ms | ✅ |
| Node restart | <5s | ≈1.3s（含端口等待） | ✅ |

## 2. Phase 26 新增基准（进程内 TCP，如实记录）

```text
PHASE26-BENCH PITR-APPEND 2717-3164 ops/s
PHASE26-BENCH PITR-RESTORE 21-38 ms（100-500 记录）
PHASE26-BENCH CDC-APPEND 5882-6451 ops/s
PHASE26-BENCH SECURITY 1M-10M ops/s
PHASE26-BENCH RESP-ENCODE 0.85M-0.91M ops/s
PHASE26-BENCH OPERATOR-PLAN 1M-5M ops/s
PHASE26-BENCH CHECKPOINT 1139-5346 ms（100-500 轮，Windows 磁盘）
PHASE26-BENCH PITR-E2E 38 ms（300 记录全链路）
```

## 3. 说明

- Linux/Docker/K8s 拓扑（Gateway×3 / Metadata×3 / Storage×6）基准
  由 CI（jvm-e2e / container-e2e / kind-e2e）执行，本报告记录本地口径；
- 跨机网络 RTT 未计入，以 CI 报告为准。
