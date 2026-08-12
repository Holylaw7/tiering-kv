# 签名合规证明指南（ADR-0182）

## 签名范围

```text
payload = index|regulation|versionId|violations|prevHash|hash|timestamp
signature = HMAC-SHA256(key, payload)
```

## 使用

```java
Signed signed = SignedAttestation.sign(attestation, key);
boolean valid = new SignatureVerifier().verify(signed, key);
```

密钥错误 / violations 篡改 / prevHash 篡改 → 一律拒绝；
签名覆盖完整证明字段，防止"仅改字段不改 hash"绕过。
