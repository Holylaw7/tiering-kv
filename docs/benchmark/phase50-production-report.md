# Phase 50 Production Report

## 口径

本报告为 LOCAL 口径；跨机/跨地域（CROSS_MACHINE）项已正式封板
（ENV_BLOCKED_FINAL），待真实 Runner 复审。

## 基准摘要

| 路径 | 工具 | 口径 |
| --- | --- | --- |
| MemTable GET | JMH（size 10K/100K） | LOCAL |
| WAL append | JMH（NO fsync） | LOCAL |
| SSTable mmap 随机读 | JMH（size 10K/100K） | LOCAL（page cache 热） |

运行：`./scripts/benchmark-jmh.sh`；结果：target/jmh-results/。

## 门禁终态

`GateConvergenceV16.summary()`：closed + envBlockedFinal +
registeredRelease 三类终态唯一，无滚动 defer。
