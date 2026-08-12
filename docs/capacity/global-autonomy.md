# 全球受限自治指南（ADR-0157）

## 编排模型

```text
预测（AutoCapacityAdvisor）
  → GlobalAutonomyOrchestrator（围栏校验）
      ├── 容量：AutonomousCapacityController
      ├── 流量：AutonomousTrafficController / GlobalTrafficAutonomy
      └── 重分片：受控回调
  → 验证 / 回滚 / 失败登记
```

## 围栏

- 日预算：maxActionsPerDay（newDay 重置）；
- 地域上限：maxRegionsAffected；
- 熔断：openCircuit 后全部拒绝，resetCircuit 恢复；
- 回滚：rollback 恢复容量初始节点 + 流量原始配额。

## 原则

"全自治" = 策略围栏内自治；禁止无约束自动变更。
