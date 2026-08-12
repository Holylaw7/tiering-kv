# 网络策略跨租户审计指南（ADR-0176）

## 审计联动

```java
NetworkPolicyAudit audit = new NetworkPolicyAudit();
new PolicyCompiler().apply(policy, dsl, audit); // 自动记录
```

## 视图

- `byTenant(audit)`：租户参与事件数；
- `byAction(audit)`：allow/deny 分布；
- `byTenantAction(audit)`：租户 × 动作；
- `forTenant(tenantId)` / `since(timestamp)`：过滤查询。

策略变更必须有来源与时间记录，禁止无审计变更。
