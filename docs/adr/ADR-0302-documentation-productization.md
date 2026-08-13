# ADR-0302: Documentation Productization

## Status

Accepted

## Context

文档为阶段流水账，第三方无法独立上手。

## Decision

采用产品文档体系：

- README 重写：5 分钟 quickstart + 能力矩阵 + 文档导航；
- docs/operations/quickstart.md + operations-runbook.md；
- API 参考（命令表 + 回复形态 + 兼容矩阵）；
- 文档检查清单：无占位、链接有效、命令可执行；
- 最终性能/容量白皮书（真实口径）。

## Alternatives

1. 继续流水账：无法交付；
2. 只写 README：深度不够。

## Consequences

优点：可独立上手、可评审。

缺点：文档维护成本。

风险：与代码漂移需检查清单测试。

## Implementation

`README.md`、`docs/operations/quickstart.md`、
`docs/operations/operations-runbook.md`、
`docs/benchmark/final-performance-whitepaper.md` +
`src/test/java/io/tieringkv/operations/DocumentationChecklistTest.java`。
