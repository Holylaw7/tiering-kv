# 合规持续证明指南（ADR-0167）

## 哈希链

```text
hash(i) = SHA-256(i | regulation | versionId | violations | hash(i-1))
```

## 使用

```java
AttestationChain chain = new AttestationChain();
chain.append(new AuditRun("GDPR", "v1", 1000, 2, "[]"));
chain.append("GDPR", "v2", 0, 2000);
boolean valid = chain.verify(); // false = 链被篡改/断裂
```

证明链可重建验证（`new AttestationChain(List<Attestation>)`），
适用于审计恢复与第三方校验。
