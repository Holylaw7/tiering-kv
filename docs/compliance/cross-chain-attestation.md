# 合规证明跨链互操作指南（ADR-0195）

## 使用

```java
CrossChainAnchor anchor = new CrossChainAnchor();
Map<String, AnchorRecord> records = anchor.anchorAll(
        Set.of("chain-1", "chain-2"), 1000, headHash);

CrossChainVerifier verifier = new CrossChainVerifier();
boolean any = verifier.verifyAny(records.values());
boolean consistent = verifier.verifyConsistent(records.values());
```

任一链有效即通过（审计方自由选链）；一致性校验要求全部有效且同头。
