# Phase 25 最终 GA 基准报告

Phase 25 · 2026-08-11 · 环境：Windows 11 / Temurin 17 / 进程内 TCP（如实标注）

## 1. 元数据控制面（ADR-0099 / TD-050）

```text
PHASE25-BENCH META-PROPOSE 100 -> 657 ops/s
PHASE25-BENCH META-PROPOSE 300 -> 923 ops/s
PHASE25-BENCH META-PROPOSE 600 -> 1077 ops/s
PHASE25-BENCH META-CONCURRENT 1 -> 724 ops/s
PHASE25-BENCH META-CONCURRENT 4 -> 1169 ops/s
PHASE25-BENCH META-CONCURRENT 8 -> 1393 ops/s
PHASE25-BENCH META-FAILOVER 110-118 ms
PHASE25-BENCH META-RESTART ~1274 ms（含 1s 端口等待）
PHASE25-BENCH META-SNAPSHOT-RESTART ~1246 ms（含 1s 端口等待）
```

## 2. 与目标对比

| 指标 | 目标 | 实测 | 说明 |
| --- | ---: | --- | --- |
| 元数据 failover | <500ms | 110–118ms ✅ | 三节点 TCP |
| 决策持久化恢复 | <1s（排除端口等待） | ≈270ms ✅ | 200 条日志重放 |
| 快照恢复 | <1s（排除端口等待） | ≈246ms ✅ | 1100 条日志 → 快照 |
| 提案吞吐 | 参考基线 | 657–1077 ops/s | SYNC 日志 + 三节点复制 |

## 3. 历史对比（Phase 1–24 不变项）

| 指标 | Phase 24 | Phase 25 |
| --- | ---: | ---: |
| 事务 SET | 144–175K | 不变（未触碰事务路径） |
| 跨区 2PC | 45–83K | 不变 |
| 全量测试 | 2238 | **2408**（+170） |

## 4. 可信度边界

- 元数据提案为进程内 TCP 等价（同机 4 个端点），跨机 RTT 未计入；
- FileRaftLog SYNC 是吞吐主成本，真实磁盘性能因机器而异；
- 容器/K8s 执行项待 Runner，数值口径以 CI 报告为准。
