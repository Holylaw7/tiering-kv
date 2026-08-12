# ADR-0222: Full Operator Coprocessor Pushdown

## Status

Accepted

## Context

Phase 43 支持 FILTER → PROJECT → AGGREGATE 链（TD-080 JVM 关闭方向）。
Phase 44 需要把 JOIN / GROUP BY / ORDER BY / LIMIT 也下推到存储层，
并保证与上层 SQL 结果一致。

## Decision

扩展 `CompoundCoprocessorRequest` 与 `CoprocessorExecutor`：

- 新增算子 JOIN（等值内连接）、GROUP_BY（分组聚合）、ORDER_BY、
  LIMIT；
- 执行链顺序固定：JOIN → FILTER → PROJECT → GROUP_BY → ORDER_BY →
  LIMIT；
- 与 SqlExecutor / 上层 SQL 结果一致性由等价性测试锁定；
- 新增 Row 元数据（groupKey）支持分组，不影响既有算子语义。

## Alternatives

1. 全部在协调器层执行：正确但读取放大；
2. 仅下推 FILTER：Phase 43 现状，无法满足规模化；
3. 直接修改 SqlEngine：耦合存储细节。

## Consequences

优点：减少跨层数据传输；算子语义单点维护。

缺点：算子链固定顺序，复杂查询需拆多次下推。

风险：JOIN 语义差异（等值 vs 非等值）需在文档中声明。

## Implementation

`sql/coprocessor/CoprocessorRequest.Operator` 扩展 +
`CoprocessorExecutor.executeCompound` 扩展 +
`src/test/java/io/tieringkv/sql/coprocessor/FullOperatorCoprocessorTest`、
`docs/sql/full-operator-pushdown.md`。
