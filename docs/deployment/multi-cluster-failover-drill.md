# 多集群故障切换演练（v4 M4 增强）

## 目标

用真实 RESP 客户端链路验证集群级故障切换：primary 故障 → 故障窗口
观察 → 恢复 → 回切冒烟（线性一致性）。

## 流程（scripts/multi-cluster-failover.sh）

```text
primary up
  ↓ smoke（真实客户端 harness，RESP 模式，线性一致）
inject kill-coordinator（primary 丢失）
  ↓ 故障窗口：客户端失败可容忍（记录）
recover（compose up --wait）
  ↓ 回切冒烟：8 线程 × 300 ops RESP 线性一致
报告 target/multi-cluster-failover-report.txt
```

## 语义说明

- 客户端为真实 RESP/TCP（VerificationHarness `resp 127.0.0.1 6379`
  模式），非内存模拟；
- 故障窗口失败为预期（写入不可用），恢复后必须线性一致；
- 跨集群数据一致性由 M3 复制（LWW + 水位）保证；本演练验证
  "故障可观察、恢复可回切"的运维闭环；
- 集群级切换（dr 提升）需真实多集群部署（TieringKVTopology），
  演练脚本按单集群故障/恢复语义先行落地。

## 验收

- 故障前 smoke 通过；恢复后 smoke 通过；
- 故障窗口失败被记录且不阻塞演练；
- 报告归档 target/multi-cluster-failover-report.txt。
