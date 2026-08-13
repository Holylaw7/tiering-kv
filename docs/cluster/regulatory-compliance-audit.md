# 监管级合规审计（ADR-0245）

## 背景

Phase 46 的合规审计链为摘要签名。Phase 47 升级为时间戳证书 +
密钥轮换 + 外部验证。

## 设计

```text
Certificate(chainDigest, issuedAt, issuer, signature, keyVersion)
issue(digest, issuer) → 签发时间戳证书
rotateKey() → 版本 +1，旧密钥进入吊销列表（验证仍可用）
verify(certificate) → 重算签名 + 时间戳 + 版本校验
```

## 联动

- AutonomousComplianceAuditor：审计链摘要作为证书输入；
- AutonomousPdUnattended / 自治控制器：合规数据来源；
- 熔断入口保留。

## 验收

- 签发/验证矩阵：35 种摘要 × 签发者；
- 轮换：1–50 次轮换后旧证书仍有效（13 项展开）；
- 篡改检测：签名 / 未来版本拒绝（20 项展开）。
