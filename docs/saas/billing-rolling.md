# 账单周期滚动

Phase 31 · ADR-0136

## 流程

```text
BillingScheduler（periodMillis）
  → 周期滚动（cycle × period）
  → 冻结 → Invoice（行项目 + 总价）
  → TenantAuditLog 审计 → 计量复位
```

## 能力

- 参数化周期（月/周/自定义）；
- 周期边界由 cycle 计算；
- 未定价维度不计费；
- 每周期审计记录。
