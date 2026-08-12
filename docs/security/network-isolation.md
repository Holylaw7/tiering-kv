# 多租户网络隔离指南（ADR-0161）

## 模型

```text
租户 → NetworkIsolationDomain（VPC / 子网 / 私有网络标志）
  → IsolationPolicy：同域允许 / 白名单允许 / 默认拒绝
```

## 使用

```java
policy.register(new NetworkIsolationDomain("t1", "vpc-1",
        "subnet-1", true));
policy.allow("t1", "t2");   // 双向授权
policy.deny("t1", "t2");    // 撤销
policy.canCommunicate("t1", "t2"); // false（默认）
```

白名单 pair 规范化（有序），双向同 key；未知租户一律拒绝。
