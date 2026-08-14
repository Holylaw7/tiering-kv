# CI Execution & v3.2 GA Release Pipeline

## 流水线

release.yml（v3.2.0）执行步骤：

```text
Test (mvn test)
  → Benchmark (Phase24..50 + Baseline 43..50)
  → Security scan (Trivy)
  → Build image
→ Publish image (ghcr.io/holylaw7/tiering-kv:v3.2.0)
  → Checksums (sha256sum)
  → Generate release notes
  → GitHub Release
```

## 执行记录

- 本仓库当前无远程地址，流水线"就绪待远程"如实登记（REL-001 /
  TD-075 终态 REGISTERED_RELEASE）；
- 配置远程后打 tag `v3.2.0-rc1` / `v3.2.0` 即触发；
- 每次发布必须：全量回归 0 failures + checksums 产物 + release notes
  定稿。

## 门禁

ReleaseV32GATest 校验：标签覆盖 v1.0.0–v3.2.0、基准套件包含
Phase50BenchmarkTest / Phase50ProductionBaselineTest、checksums
步骤存在、GA release notes 存在、版本文档一致。
