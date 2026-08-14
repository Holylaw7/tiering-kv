# Versioning & Artifacts

## 版本模型

pom.xml 使用 CI-friendly 版本：

```xml
<version>${revision}</version>
<properties><revision>3.7.0-SNAPSHOT</revision></properties>
```

发布时注入正式版本：`mvn -Drevision=3.7.0 package`；
flatten-maven-plugin 保证打包 pom 不含占位符。

## 一致性校验

`scripts/version-check.sh` 校验 pom revision 出现在：

- CHANGELOG.md / README.md / ROADMAP.md
- docs/release/v3.7.0-release-notes.md
- scripts/release-notes.sh

任一缺失即退出非零，禁止发布。

## 制品

- fat jar：`target/tiering-kv-${revision}.jar`
- Docker image：`ghcr.io/Holylaw7/tiering-kv:${tag}`
- checksums：发布流水线生成 `sha256sum` 清单

## 发布动作

```bash
./scripts/version-check.sh
mvn -q test
./scripts/quality-gates.sh
mvn -q -Drevision=3.2.0 package
```
