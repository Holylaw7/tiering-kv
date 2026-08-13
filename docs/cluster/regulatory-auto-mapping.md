# 监管法规自动映射 + 证据链（ADR-0252）

## 背景

Phase 47 的监管证书提供时间戳证明。Phase 48 增加法规条款 → 审计证据
自动映射。

## 设计

```text
registerRule(regulation, clause, eventType)
mapEvent(eventType) → 命中条款拼接 + append-only 证据链
evidenceCount(eventType) → 事件计数
```

## 联动

- RegulatoryComplianceCertificate：证据链摘要可作为证书输入；
- AutonomousComplianceAuditor / 自治控制器：合规数据来源；
- 熔断入口保留。

## 验收

- 映射矩阵：35 种法规/条款/事件组合（35 项展开）；
- 多条款命中（GDPR+CCPA）；证据链 append-only + 并发稳定；
- 事件数 1–5000（13 项展开）。
