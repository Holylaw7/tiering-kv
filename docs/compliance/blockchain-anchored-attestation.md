# 合规证明链上锚定指南（ADR-0188）

## 锚定

```text
anchorHash = SHA-256(chainId | blockId | timestamp | headHash)
```

## 使用

```java
AnchorRecord record = ChainAnchor.anchor("chain-1", "block-42",
        1000, attestationChainHead);
boolean valid = new ChainVerifier().verify(record, expectedHead);
```

篡改 head / block / 时间 → 验证失败；锚定缺失 → 调用方拒绝。
