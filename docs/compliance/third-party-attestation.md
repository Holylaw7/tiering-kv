# 第三方合规证明指南（ADR-0174）

## 独立验证

```java
AttestationExporter exporter = new AttestationExporter();
AttestationVerifier verifier = new AttestationVerifier();
String json = exporter.toJson(chain);
boolean valid = verifier.verify(exporter.fromJson(json));
```

## 语义

- 导出格式：JSON 数组（index/regulation/versionId/violations/
  prevHash/hash/timestampMillis）；
- 验证不依赖原链状态，可离线校验；
- 篡改 violations / 断裂 prevHash → verify 返回 false。
