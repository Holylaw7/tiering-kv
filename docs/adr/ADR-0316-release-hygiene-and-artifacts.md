# ADR-0316: Release Hygiene & Artifacts

## Status

Accepted

## Context

发布版本/制品需要可审计规范。

## Decision

采用发布卫生：semver + GA/patch/rc 规范、SBOM/签名/归档策略
（`sbom.sh`）。

## Consequences

优点：制品可审计。

缺点：SBOM 生成依赖工具链。

风险：签名密钥需托管。

## Implementation

`docs/operations/release-hygiene.md`、`scripts/sbom.sh` +
`src/test/java/io/tieringkv/operations/ReleaseHygieneTest.java`。
