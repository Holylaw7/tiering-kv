# 合规即代码指南（ADR-0159）

## 法规版本化

```java
store.register(new Version("GDPR", "v1", effectiveFrom, controls));
store.register(new Version("GDPR", "v2", later, controls));
store.activate("GDPR", "v1", switchTime); // 回滚到旧版本控制项
```

## 持续审计

```java
AuditRun run = pipeline.evaluate("GDPR", now,
        controls -> evaluateControls(controls));
// run: versionId / violations / exportJson / ranAtMillis
```

未到生效时间的法规无有效版本 → 明确抛错，禁止静默跳过审计。
