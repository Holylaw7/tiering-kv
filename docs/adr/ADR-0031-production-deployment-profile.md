# ADR-0031: Production Deployment Profile

## Status

Accepted

## Context

生产部署需要一组推荐参数：CPU/内存、JVM 选项、线程数、WAL 策略、缓存与
水位配置。当前 Main 使用默认值，未针对生产验证。

## Decision

定义推荐部署画像（Phase 9 实测校准，部署时可按容量模型缩放）：

```text
推荐规格：4C8G 起步；8C16G 为单节点基线
JVM：G1、-Xmx=物理内存 50%、-XX:MaxDirectMemorySize=512m、
     -XX:+UseStringDeduplication（可选）、JFR 默认开启（生产按需）
线程：KeyShardExecutor = min(16, 核数)；TierWorkerPool = 1 flush + 2 migration
WAL：EVERY_SEC（默认，丢失窗口 ≤1s）；强一致场景 ALWAYS
BlockCache：容量 1024 块（≈4MB），可调
HotKey：窗口 1000ms、阈值 1000、TTL 500ms
水位：70 / 85 / 95；背压超时 1000ms
```

1. 数据目录独立（wal / cold / migration 分目录）；
2. 独立 JVM + 独立日志；压测客户端独立进程（不共享 JVM）；
3. 上线前以 Workload A/B/C 回归吞吐与 P99。

## Alternatives

1. 全默认：未验证；
2. 一次性大堆：GC 停顿风险高。

## Consequences

**优点：** 参数可复现、可调优、可运维。
**缺点：** 画像随版本演进需维护。
**风险：** 不同工作负载最优参数不同 → 画像给出基线，容量模型给出缩放。

## Implementation

- `docs/benchmark/deployment-profile.md`；config 骨架已在仓库；
- Phase 10 将参数接入配置加载。
