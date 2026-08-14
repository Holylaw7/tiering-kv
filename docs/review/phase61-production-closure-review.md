# Phase 61 Review — v4.0 M4 Production Closure

## 总体结论

v4.0 M4（ADR-0322）完成：容量模型、Operator 状态机与多集群编排、
Jepsen 外部化、冷/热性能基线、真实 Runner GA 门禁 7/7 全绿。
全量回归 **14668 tests / 0 failures**（本地），真实 Runner 7/7
（含 release v4.0.0-rc1）。

## 交付清单

1. **CapacityModel**（TD-019）：QPS/值大小/读占比/副本/保留/活跃键 →
   内存、磁盘、吞吐、延迟四维估算（可计算可测试）；
2. **benchmark.sh** 真实入口：核心 benchmark 套件 + PHASExx-BENCH
   输出归档；
3. **Operator 状态机**：PENDING → PROVISIONING → READY → UPGRADING /
   BACKING_UP / RESTORING → READY（任意阶段 FAILED 可重试），
   Controller 按 spec/status 自动推进；
4. **多集群编排**：MultiClusterTopology（复制边校验）+ Planner
   （CONNECT/DISCONNECT/NOOP）+ TieringKVTopology CRD sample +
   多云部署文档（M3 通道接线到 Operator 模型）；
5. **Jepsen 外部化**：scripts/jepsen-run.sh（容器故障注入 ×4 +
   VerificationHarness 独立 JVM 线性一致性回归）+ jepsen-e2e job
   （真实 Runner 通过）；
6. **冷/热基线**（TD-009）：ColdCacheBenchmarkTest + cold-cache-bench.sh；
   20K × 64 维 mmap 全量读取冷 119.8ms vs 热 18.9ms，6.3x；
7. **发布支持**：release.yml 增加 v4.0.0 标签，release-notes.sh v4.0
   说明块（无模板污染）。

## 测试与门禁

- 新增测试 21 项（CapacityModel 7 + 状态机 12 + 多集群 9，含增量），
  surefire 口径 14668；
- 真实 Runner：build / test / transaction-e2e（5 jobs 含 jepsen-e2e）
  × main/develop + release = **7/7 全绿**；
- GitHub Release v4.0.0-rc1 已发布（说明正确）；
- ghcr 镜像：ghcr.io/holylaw7/tiering-kv:v4.0.0-rc1。

## 已知限制（如实记录）

- Operator 状态机为 Java 模型 + CRD sample，真实 K8s controller
   reconcile 循环接线（Java operator 框架）未实现；
- Jepsen 外部化以容器故障注入 + 独立 JVM 线性一致性回归组合证据，
  未接入真实客户端协议链路（M4 后增强）；
- 冷口径进程内模拟 + root drop caches 双口径，runner 无 root 时
  仅进程内口径；
- 跨集群 2PC、CRDT 冲突演进不在 v4.0 范围。

## 后续

- Optimization Roadmap P1（存储引擎三件套：leveled compaction /
  MemTable 轮转 / 迁移队列）启动；
- M4 增强项（K8s controller 接线、真实客户端 Jepsen、多集群故障
  切换演练）按路线图排期。
