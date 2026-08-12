# 法规合规自动化指南（ADR-0153）

## 组件

- `RegulationMapper`：法规 → 控制项（implemented）+ 覆盖率 +
  缺失项；
- `ComplianceReport`：违规项 + 严重级（LOW/MEDIUM/HIGH/CRITICAL）；
- `AuditExporter`：审计与合规报告的 JSON/CSV 导出（转义安全）。

## 使用

```java
mapper.register("GDPR", new Control("g1", "residency", true), ...);
double coverage = mapper.coverage("GDPR");
report.add(new Violation("GDPR", "g3", Severity.HIGH, "..."));
String json = new AuditExporter().toJson(report);
String csv = new AuditExporter().toCsv(report);
```

导出格式必须可参数化验收，法规映射版本化维护。
