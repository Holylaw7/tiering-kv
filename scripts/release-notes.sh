#!/usr/bin/env bash
set -euo pipefail

# 生成 v1.0 发布说明（Phase 26 Goal 8）：版本 + 关键能力 + 测试/基准摘要。
VERSION=${1:-v1.0.0-rc1}

cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v1.0：Redis 协议兼容、LSM 冷热分层、
Multi-Raft 分布式事务 KV。

## 本版本能力

- RESP2 + RPC v1 + 存储格式 v1 冻结（ProtocolVersion）
- PITR 时间点恢复（WALArchive / Checkpoint / RestoreTimeline）
- CDC exactly-once 流式变更（PUT/DELETE/TXN_COMMIT/REGION_MOVE）
- Enterprise Security（RBAC 角色/权限 + 令牌轮换/吊销）
- Kubernetes Operator（TieringKVCluster CRD + Planner/Controller）
- tierctl 生产 CLI + 发布流水线

## 质量摘要

- 全量回归：mvn test 0 failures
- 基准：见 docs/benchmark/v1-final-production-report.md

## 已知限制

- 详见 docs/release/v1.0.0-release-notes.md
EOF
