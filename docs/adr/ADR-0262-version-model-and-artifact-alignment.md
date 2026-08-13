# ADR-0262: Version Model & Artifact Alignment

## Status

Accepted

## Context

pom.xml 长期停留在 0.1.0-SNAPSHOT，而发布文档已宣称 v3.2.0；git 没有
版本 tag。版本失真使"发布候选"无法被验证，是产品化最直接的硬伤。

## Decision

采用 CI-friendly 版本模型：

- pom `<version>${revision}</version>`，`<revision>` 默认
  `3.2.0-SNAPSHOT`，发布流水线注入正式版本号；
- flatten-maven-plugin 解析 ${revision}，保证打包 pom 不含占位符；
- `scripts/version-check.sh` 校验 pom / release notes / CHANGELOG /
  README 版本一致性；
- 发布产物：fat jar + Docker image tag + SHA-256 checksums。

## Alternatives

1. 继续固定 SNAPSHOT 版本：发布版本不可追溯；
2. 每次手动改 pom：容易遗漏，发布不可重复；
3. 多模块版本管理插件：单模块项目过重。

## Consequences

优点：版本单一事实来源、发布可注入、制品可校验。

缺点：构建需支持 ${revision}（Maven 3.5+，本项目 3.9 满足）。

风险：其他工具直接读 pom 版本需兼容 ${revision}。

## Implementation

`pom.xml`、`scripts/version-check.sh`、
`src/test/java/io/tieringkv/operations/VersionConsistencyTest.java`、
`docs/operations/versioning-and-artifacts.md`。
