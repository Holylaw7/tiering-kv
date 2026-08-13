# Quality Gates

## 门禁

| 门禁 | 工具 | 触发 |
| --- | --- | --- |
| 覆盖率 | JaCoCo + scripts/coverage-check.sh | mvn test 生成报告；脚本校验阈值 |
| 静态分析 | SpotBugs | scripts/quality-gates.sh（不阻塞构建） |
| 依赖审计 | maven-dependency-plugin | scripts/quality-gates.sh |

## 覆盖率阈值

`COVERAGE_THRESHOLD` 环境变量（默认 70% line；Phase 50 GA 全量回归
实测 line 覆盖率 92.32%）；低于阈值 coverage-check.sh 退出非零，
禁止伪报达标。

## 使用

```bash
mvn -q test                 # 生成 target/site/jacoco/jacoco.csv
./scripts/coverage-check.sh # 覆盖率门禁
./scripts/quality-gates.sh  # 三件套一键运行
```

门禁不达标必须如实报告，禁止降级绕过。
