# ADR-0100: CI Container Chaos Pipeline

## Status

Accepted

## Context

Phase 24 交付 `.github/workflows/transaction-e2e.yml` 与 JVM 等价 E2E
（CiTransactionE2ETest 31 项），但真实容器编排运行未执行（TD-048）。
容器级故障（kill coordinator/participant/metadata、tc netem 分区）是
验证"已提交事务不丢失、无重复提交"的必要场景。

## Decision

GitHub Actions ubuntu-latest 执行完整容器混沌管道：

1. build image → `docker compose up -d --wait` → 健康检查 → 事务套件
   （容器内运行 CiTransactionE2ETest）；
2. 容器故障注入：docker kill coordinator / participant / metadata leader，
   以及 tc netem 网络分区；
3. 每次故障后重启容器 + recover，断言无提交丢失、无重复提交；
4. 收集日志 → cleanup；同一 Runner 连续 3 次全绿为验收。

## Alternatives

1. 仅 JVM 等价测试：无法覆盖真实进程隔离、网络栈与容器生命周期；
2. 独立裸机 Jenkins：维护成本高，且无托管 Runner 的可复现性。

## Consequences

优点：容器链路获得真实执行记录，TD-048 关闭；故障注入脚本可复用。

缺点：GitHub Actions 分钟配额消耗；容器测试依赖 Docker 环境，本地跳过。

风险：镜像构建与 compose 启动偶发时序，需健康检查等待 + 重试。

## Implementation

代码影响范围：

- `.github/workflows/transaction-e2e.yml`（补充故障注入步骤）；
- `scripts/container-chaos.sh`（kill/分区/恢复断言）；
- `tests/container`（容器内运行的 tagged 测试）。
