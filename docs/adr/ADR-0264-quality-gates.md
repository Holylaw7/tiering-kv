# ADR-0264: Quality Gates

## Status

Accepted

## Context

构建只有编译 + 测试，无覆盖率、静态分析、依赖审计。质量无法度量，
回归只能依赖测试数量。

## Decision

采用可运行、可解释、达标才放行的质量门禁：

- JaCoCo：测试覆盖率报告 + `scripts/coverage-check.sh` 阈值校验
  （line 阈值记录于脚本，不达标退出非零）；
- SpotBugs：静态分析（构建期不阻塞，报告供评审）；
- maven-dependency-plugin：未使用/未声明依赖分析；
- `scripts/quality-gates.sh` 一键运行三件套并输出报告路径；
- 门禁不达标必须如实报告，禁止降级绕过。

## Alternatives

1. 只测数量不看覆盖：质量盲区；
2. 全量门禁直接绑定 mvn test：历史存量不达标会阻塞一切交付；
3. 无静态分析：常见缺陷只能靠人工评审。

## Consequences

优点：覆盖可度量、静态缺陷可发现、依赖可审计。

缺点：覆盖率阈值需要随基线演进调整。

风险：阈值失真会导致门禁失去意义，需定期校准。

## Implementation

`pom.xml`、`scripts/coverage-check.sh`、`scripts/quality-gates.sh`、
`src/test/java/io/tieringkv/operations/QualityGateTest.java`、
`docs/operations/quality-gates.md`。
