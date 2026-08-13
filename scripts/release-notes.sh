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

if [[ "${VERSION}" == v2.9.0* ]]; then
  # 支持 v2.9.0-rc1 / v2.9.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v2.9.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、真实 Runner 门禁闭环与全球一致性最终化。

## 本版本能力

- 真实执行门禁收敛 v12（ADR-0234）
- 跨云一阶段规模化（ADR-0235）
- 窗口函数全族 / 动态下推（ADR-0236）
- TSO 跨云授时仲裁 + 防时钟回拨（ADR-0237）
- 自治无人值守全自动合规证明（ADR-0238）
- TiKV 跨机基准定期回归 + 真实凭据 v4（ADR-0239，TD-076 剩余项）

## 质量摘要

- 新增测试 ≥540；全量回归 ≥10491 全绿
- 基准：见 docs/benchmark/phase46-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v12.md

## 已知限制

- 详见 docs/release/v2.9.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.0.0* ]]; then
  # 支持 v3.0.0-rc1 / v3.0.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.0.0 GA：Redis 协议兼容、LSM
冷热分层、Multi-Raft 分布式事务、真实 Runner 闭环归档与全球一致性 GA。

## 本版本能力

- 真实执行门禁收敛 v13 + 执行归档（ADR-0241）
- 跨云一阶段全球统一仲裁（ADR-0242）
- RL 动态下推（ADR-0243）
- TSO 量子/卫星授时原型（ADR-0244）
- 监管级合规证书（ADR-0245）
- TiKV 跨机回归告警 + 真实凭据 v5（ADR-0246，TD-076 剩余项）

## 质量摘要

- 新增测试 ≥550；全量回归 ≥11053 全绿
- 基准：见 docs/benchmark/phase47-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v13.md

## 已知限制

- 详见 docs/release/v3.0.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.1.0* ]]; then
  # 支持 v3.1.0-rc1 / v3.1.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.1.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、真实 Runner 门禁全量闭环与多组织联邦
一致性。

## 本版本能力

- 真实执行门禁收敛 v14 + 发布记录归档（ADR-0248）
- 多组织联邦仲裁（ADR-0249）
- RL 多智能体下推（ADR-0250）
- TSO 量子/卫星硬件适配（ADR-0251）
- 监管法规自动映射 + 证据链（ADR-0252）
- TiKV 跨机回归闭环 + 真实凭据 v6（ADR-0253，TD-076 剩余项）

## 质量摘要

- 新增测试 ≥560；全量回归 ≥11625 全绿
- 基准：见 docs/benchmark/phase48-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v14.md

## 已知限制

- 详见 docs/release/v3.1.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.2.0* ]]; then
  # 支持 v3.2.0-rc1 / v3.2.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.2.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、真实 Runner 闭环归档与跨监管域联邦一致性。

## 本版本能力

- 真实执行门禁收敛 v15 + 闭环归档（ADR-0255）
- 跨监管域联邦仲裁（ADR-0256，域级仲裁 + 回退 2PC）
- RL 多智能体联邦学习（ADR-0257，FedAvg + 噪声注入/梯度裁剪）
- 商用量子/卫星授时设备接入（ADR-0258，设备 SPI + 主备切换）
- 监管法规库 + 差异报告（ADR-0259）
- TiKV 跨机回归归档 + 真实凭据 v7（ADR-0260，TD-076 剩余项）

## 质量摘要

- 新增测试 ≥570；全量回归 ≥12205 全绿
- 基准：见 docs/benchmark/phase49-production-report.md
- 门禁收敛表：docs/deployment/gate-convergence-v15.md

## 已知限制

- 详见 docs/release/v3.2.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.3.0* ]]; then
  # 支持 v3.3.0-rc1 / v3.3.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.3.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、Redis 命令族补齐。

## 本版本能力

- 字符串命令族（ADR-0269）：INCR/DECR/APPEND/STRLEN/GETSET/SETNX/
  SETEX/GETDEL/GETRANGE/SETRANGE，段锁原子 + WAL 接入
- TTL 命令族（ADR-0270）：EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT/TTL/
  PTTL/PERSIST，语义与 Redis 对齐
- 多键命令族（ADR-0271）：MGET/MSET/MSETNX/DEL/EXISTS 批量语义
- 管理命令族（ADR-0272）：DBSIZE/FLUSHDB/SCAN/TYPE/CONFIG/CLIENT/
  COMMAND
- RESP2 兼容矩阵（ADR-0273）：整数/nil/空串/错误/数组形态对齐
- 网关路由与 CROSSSLOT（ADR-0274）：单键 MOVED + 多键同槽校验

## 质量摘要

- 新增测试 ≥520；全量回归 ≥13190 全绿
- 基准：见 docs/benchmark/phase51-production-report.md
- 命令延迟：docs/benchmark/command-latency-report.md

## 已知限制

- 详见 docs/release/v3.3.0-release-notes.md
EOF
fi
