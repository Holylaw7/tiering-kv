#!/usr/bin/env bash
set -euo pipefail

# 生成发布说明：按版本匹配输出，无匹配时输出最小占位。
# 注意：不得在开头无条件输出模板（曾导致每个版本都拼接 v1.0 内容）。
VERSION=${1:-v1.0.0-rc1}

if [[ "${VERSION}" == v1.0.0* ]]; then
  # 支持 v1.0.0-rc* / v1.0.0 发布标签
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
fi

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

if [[ "${VERSION}" == v3.4.0* ]]; then
  # 支持 v3.4.0-rc1 / v3.4.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.4.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、数据结构与 RESP3/PubSub。

## 本版本能力

- 类型化值编码（ADR-0276）：HASH/LIST/SET/ZSET 标签 + 序列化 + 键级 TTL
- Hash 命令族（ADR-0277）：HSET/HGET/HDEL/HGETALL/HINCRBY 等
- List 命令族（ADR-0278）：LPUSH/RPUSH/LPOP/RPOP/LRANGE 等
- Set 命令族（ADR-0279）：SADD/SREM/SINTER/SUNION/SDIFF 等
- ZSet 命令族（ADR-0280）：ZADD/ZSCORE/ZRANGE/ZINCRBY 等
- RESP3 协议演进（ADR-0281）：Map/Set/Double/Push + HELLO 3
- Pub/Sub（ADR-0282）：本地 broker + 模式订阅 + 集群广播 SPI

## 质量摘要

- 新增测试 ≥560；全量回归 ≥13700 全绿
- 基准：见 docs/benchmark/phase52-production-report.md
- 数据结构延迟：docs/benchmark/data-structure-latency-report.md

## 已知限制

- 详见 docs/release/v3.4.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.5.0* ]]; then
  # 支持 v3.5.0-rc1 / v3.5.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.5.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、RESP3 接线 / PubSub 网络 / 事务队列。

## 本版本能力

- RESP3 连接级接线（ADR-0283）：HELLO 3 按连接切换编码器
- Pub/Sub 连接级投递（ADR-0284）：连接订阅者 + Push + 有界队列
- 集群广播 RPC（ADR-0285）：Netty 转发 + 环回抑制 + 失败登记
- 高级数据结构命令（ADR-0286）：HSCAN/LINSERT/LMOVE/ZRANGEBYLEX 等
- MULTI/EXEC 事务队列（ADR-0287）：QUEUED + 原子批量应用
- 连接生命周期清理（ADR-0288）：断线退订 + 状态重置

## 质量摘要

- 命令注册表 101 个；新增测试 ≥560；全量回归 ≥14140 全绿
- 基准：见 docs/benchmark/phase53-production-report.md

## 已知限制

- 详见 docs/release/v3.5.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.6.0* ]]; then
  # 支持 v3.6.0-rc1 / v3.6.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.6.0：Redis 协议兼容、LSM 冷热
分层、Multi-Raft 分布式事务、事务加固 / Stream / 生产验证。

## 本版本能力

- WATCH 版本守卫（ADR-0290）：乐观并发校验 + EXEC abort
- EXEC 原子性与回滚（ADR-0291）：快照回滚 + ExecJournal
- Stream 数据类型（ADR-0292）：XADD/XREAD/XLEN/XRANGE/XTRIM
- 阻塞命令（ADR-0293）：BLPOP/BRPOP 超时语义
- 过期事件通知（ADR-0294）：keyspace 事件 + 开关
- SQL/向量生产化（ADR-0295）：错误码 + EXPLAIN + HNSW 持久化

## 质量摘要

- 命令注册表 109 个；新增测试 ≥600；全量回归 ≥14470 全绿
- 基准：见 docs/benchmark/phase54-production-report.md

## 已知限制

- 详见 docs/release/v3.6.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.7.0* ]]; then
  # 支持 v3.7.0-rc1 / v3.7.0 发布标签
  cat <<EOF
# Tiering-KV ${VERSION} Release Notes

## 定位

Enterprise-ready Distributed Database v3.7.0：分布式正确性验证、Stream
消费组、事务日志持久化、文档产品化。

## 本版本能力

- 线性一致性验证（ADR-0297）
- Raft 边角验证矩阵（ADR-0298）
- 升级/备份演练（ADR-0299）
- Stream 消费组（ADR-0300）
- 跨段事务日志持久化（ADR-0301）
- 文档产品化（ADR-0302）

## 质量摘要

- 命令注册表 113 个；新增测试 ≥600；全量回归 ≥14730 全绿
- 白皮书：docs/benchmark/final-performance-whitepaper.md

## 已知限制

- 详见 docs/release/v3.7.0-release-notes.md
EOF
fi

if [[ "${VERSION}" == v3.7.0-ga ]]; then
  cat <<EOF
# Tiering-KV v3.7.0 GA Release Notes

GA 发布：分布式正确性验证、消费组、事务持久化、文档产品化已完成；
真实 Runner 门禁 SEALED_GA 封板声明（无远程环境）。
EOF
fi

if [[ "${VERSION}" == v3.7.1* ]]; then
  cat <<EOF
# Tiering-KV ${VERSION} Maintenance Release Notes

维护模式首个补丁版本：真实 GitHub Runner 门禁修复集合，无功能变更。

## 本版本修复

- 真实 Runner 门禁全绿：build / test / transaction-e2e / release
  连续多轮 7/7（含规划提交回归验证）
- GHCR 镜像命名：统一为 ghcr.io/holylaw7/tiering-kv（owner 全小写）
- 依赖漏洞：netty 4.1.136.Final / slf4j 2.0.17 / logback 1.5.34，
  Trivy 0 漏洞
- 容器入口契约：事务 compose 与 K8s start.sh 显式 TxnRuntimeMain
- CI 稳定化：TestPorts 端口分配器、surefire 失败重跑、
  Docker BuildKit 重试、benchmark 组与功能门禁分离（71 类补全）

## 质量摘要

- 全量回归：mvn test 0 failures（功能门禁 + release Benchmark 71 类）
- 门禁证据：Actions 连续全绿（commit 580ae34 → cd3db80 → 7b5de37）
- 发布说明：docs/release/v3.7.1-rc-maintenance-notes.md
EOF
fi
