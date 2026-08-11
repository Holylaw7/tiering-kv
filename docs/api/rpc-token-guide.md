# RPC 帧级令牌指南

Phase 28 · ADR-0119

## 信封版本

- v0（旧帧）：`[groupIdLen][groupId][payload]`，无令牌；
- v1（新帧）：`0x54 [groupIdLen][groupId][tokenLen][token][payload]`。

## 校验

`MultiRaftEndpoint` 解析令牌 → `RpcPermissionGuard.require(token, type)`
按消息类型授权；严格模式下无令牌帧拒绝（默认放行兼容旧客户端）。

## 使用

```java
endpoint.callAuthenticated(target, groupId, type, payload, token);
```

## 限制

- 令牌截获风险由 mTLS（ADR-0046）兜底；
- 旧帧在严格模式下被拒（配置化）。
