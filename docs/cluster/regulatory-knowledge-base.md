# Regulatory Knowledge Base & Diff Reporting

## 设计

法规库（ADR-0259）提供法规版本化存储与差异报告：

```text
RegulationDocument (regulationId + version + clauses + digest)
    ↓
RegulatoryKnowledgeBase
    ├── 版本注册（条款规范化 + SHA-256 摘要）
    ├── 条款差异（added / removed / changed）
    ├── 差异报告（可导出文本）
    ├── 摘要校验（verify）
    └── 轮换（retire，旧版本仍可验证）
```

## 可验证性

- 每个版本保存条款摘要，`verify(regulationId, version)` 重算摘要
  比对；
- 轮换只标记废弃，不删除历史，审计可追溯；
- 差异报告输出 added/removed/changed 三行，可直接归档。

## 联动

- `RegulatoryMappingEngine`：事件 → 法规条款 → 证据链；
- `RegulatoryComplianceCertificate`：基于法规版本摘要签发合规证书。

## 接入点

`io.tieringkv.cluster.scheduler.RegulatoryKnowledgeBase`，测试见
`RegulatoryKnowledgeBaseTest`（版本矩阵 / 差异矩阵 / 报告矩阵 /
校验矩阵）。
