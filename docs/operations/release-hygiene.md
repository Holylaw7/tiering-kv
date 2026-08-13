# Release Hygiene

## 版本策略

- semver：GA（X.Y.0）/ patch（X.Y.Z）/ rc 后缀；
- 冻结协议不变；additive 扩展需 ADR。

## 制品

- `scripts/sbom.sh`：SBOM + checksums；
- 签名密钥托管；归档保留发布记录。
