# 网络策略风险评分指南（ADR-0184）

## 评分规则（0~100）

```text
score = min(100, allowPairs × 10 + (privateExposure ? 20 : 0))
```

## 使用

```java
RiskScore risk = new PolicyRiskScorer().score(policy);
Map<String, Long> exposure = new RiskDashboard()
        .exposureByTenant(policy);
Map<String, Integer> scores = new RiskDashboard()
        .scoreByTenant(policy);
```

规则驱动、可解释；评分变化可关联审计事件（PolicyAuditView）。
