# 自治无人值守全自动合规证明（ADR-0238）

## 背景

Phase 45 的合规报告为摘要。Phase 46 提供全自动合规证明：审计链 +
签名 + 外部审计接口。

## 设计

```text
record(entry) → SHA-256 签名，append-only 审计链
exportAudit() → 外部审计接口
verify(exported) → 重算签名 + 链式校验（条目/签名篡改检测）
```

## 联动

- AutonomousPdUnattended：每轮执行记录到审计链；
- AutonomousPdFullAutomation / TopologyDiscovery / 自治控制器：
  合规数据来源；
- 熔断入口保留。

## 验收

- 记录矩阵：40 种前缀 × 条数（40 项展开）；
- 签名：64 位 hex + 确定性（15 项展开）；
- 篡改检测：条目 / 签名篡改均返回 false；
- 链大小：1–100 条（8 项展开）。
