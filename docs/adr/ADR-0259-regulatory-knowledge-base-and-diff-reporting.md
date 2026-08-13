# ADR-0259: Regulatory Knowledge Base & Diff Reporting

## Status

Accepted

## Context

Phase 48 法规自动映射（ADR-0252）只维护规则与证据链。Phase 49 需要法规
版本化存储、条款差异计算与差异报告，供合规审计与法规更新。

## Decision

采用 `RegulatoryKnowledgeBase`：

- 法规文档版本化（regulationId + version + 条款集合）；
- 条款差异计算（新增/删除/变更）与差异报告生成（可导出）；
- 校验（条款摘要 SHA-256）+ 轮换（旧版本可验证但标记废弃）；
- 与 RegulatoryMappingEngine / RegulatoryComplianceCertificate 联动。

## Alternatives

1. 直接覆盖法规文本：历史不可追溯；
2. 全部保留全量副本：存储膨胀且无差异视图；
3. 无校验导出：审计证据不可验证。

## Consequences

优点：法规演进可追溯、差异报告可验证可导出、轮换安全。

缺点：条款规范化的前期成本较高。

风险：法规源文本差异口径需要人工确认。

## Implementation

`src/main/java/io/tieringkv/cluster/scheduler/RegulatoryKnowledgeBase.java`
+ `src/test/java/io/tieringkv/cluster/scheduler/RegulatoryKnowledgeBaseTest.java`、
`docs/cluster/regulatory-knowledge-base.md`。
