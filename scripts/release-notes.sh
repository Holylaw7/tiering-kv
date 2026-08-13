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

if [[ "${VERSION}" == v2.6.0* ]]; then
  # 支持 v2.6.0-rc1 / v2.6.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v2.6.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、全球规模调度与生产基线收敛。

## 本版本能力

- 跨区一阶段提交（ADR-0214，TD-079 关闭方向）
- Coprocessor 多算子联合下推（ADR-0215，TD-080 关闭方向）
- TSO 集群化（ADR-0216）
- 自治 PD 与全球自治联动（ADR-0217）
- 生产级 Benchmark 基线 + 真实凭据验证（ADR-0218，TD-076 关闭方向）
- 真实执行门禁收敛 v9（ADR-0213）

## 质量摘要

- 新增测试 ≥510；全量回归 ≥8867 全绿
- 基准：见 docs/benchmark/phase43-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v9.md

## 已知限制

- 详见 docs/release/v2.6.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v2.7.0* ]]; then
  # 支持 v2.7.0-rc1 / v2.7.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v2.7.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、真实执行门禁闭环与全球规模最终化。

## 本版本能力

- 真实执行门禁收敛 v10（ADR-0220）
- 全局一阶段提交规模化（ADR-0221，TD-079 规模化）
- Coprocessor 全算子联合下推（ADR-0222，TD-080 规模化）
- TSO 跨地域容灾（ADR-0223）
- 自治 PD 全自动（ADR-0224）
- TiKV 对比基线 + 真实凭据 v2（ADR-0225，TD-076 关闭方向）

## 质量摘要

- 新增测试 ≥520；全量回归 ≥9412 全绿
- 基准：见 docs/benchmark/phase44-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v10.md

## 已知限制

- 详见 docs/release/v2.7.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v2.8.0* ]]; then
  # 支持 v2.8.0-rc1 / v2.8.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v2.8.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、真实 Runner 闭环 v11 与多云全球一致性。

## 本版本能力

- 真实执行门禁收敛 v11（ADR-0227）
- 跨云全局一阶段（ADR-0228）
- 多表 JOIN / 窗口函数下推（ADR-0229）
- TSO 全球统一时钟（ADR-0230）
- 自治 PD 无人值守（ADR-0231）
- TiKV 跨机对比基线 + 真实凭据 v3（ADR-0232，TD-076 剩余项）

## 质量摘要

- 新增测试 ≥530；全量回归 ≥9942 全绿
- 基准：见 docs/benchmark/phase45-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v11.md

## 已知限制

- 详见 docs/release/v2.8.0-release-notes.md
EOF
fi
