# ADR-0354: RPC ERROR Frame Type-Checked Decode (Raft Term Corruption Fix)

## Status

Accepted

## Context

CI 失败审计（2026-08-16）发现 `MultiRaftTransportTest` 在注销/关闭一个
节点的组路由后出现 15s propose 超时 flake。本地诊断复现出真实异常：

```text
DIAG after: n1=CANDIDATE:term=7957614742219420718 ...
```

term 被污染为天文数字。根因链路：

1. 服务端 `MultiRaftEndpoint.handle` 对已注销组抛
   `IllegalStateException("no raft group ...")`，被 `errorFrame` 转为
   `RpcMessageType.ERROR` 帧，payload 为 UTF-8 错误文本；
2. 发送方 `MultiRaftTransport` / `NettyRaftTransport` 在
   `appendEntries/requestVote/installSnapshot/timeoutNow` 中
   **无条件按对应 RESPONSE 类型解码 payload**；
3. `"no raft group gB on n1"` 的前 8 字节（ASCII）被
   `RaftMessageCodec.decodeAppendEntriesResponse` 解析为巨大 long term；
4. leader 认为收到更高 term → `stepDown` → 该垃圾 term 经后续消息
   扩散到全集群，多数派永远无法形成，propose 悬挂直至超时。

## Decision

**RPC 响应必须类型校验后再解码**：

- `MultiRaftTransport` 与 `NettyRaftTransport` 四个方法统一走
  `decodeExpected(call, expectedType, decoder)`：
  - 帧类型不等于期望 RESPONSE 类型 → `failedFuture`（按对端失败处理，
    不信任 ERROR 帧中的任何字段，尤其是 term）；
  - 类型匹配但解码异常 → `failedFuture`，同样不污染状态；
- 服务端 `errorFrame` 语义不变（ERROR 帧合法存在，只是客户端不得
  按响应解码）。

## Alternatives

1. 服务端对未注册组返回伪造的成功响应：掩盖错误，错误传播更难发现；
2. 客户端解码前检查 payload 长度/魔数：Raft 响应无魔数，脆弱；
3. 仅修测试（把 `.isZero()` 断言保留）：掩盖真实 term 污染缺陷。

## Consequences

优点：

- 消除 ERROR 帧污染 Raft term 的真实缺陷；
- 组注销/端点关闭后，leader + 存活 follower 能正常完成多数派提交；
- 回归测试覆盖“错误帧不得污染 term”与“多数派提交 + term 健全”。

缺点：

- 无。类型校验是纯防御性成本（一次枚举比较）。

风险：

- 低；变更面限定两个 Raft 传输的响应解码路径，全量回归 + Runner 门禁
  验证。

## Implementation

- `MultiRaftTransport` / `NettyRaftTransport`：`decodeExpected` +
  四个方法类型校验；
- `MultiRaftTransportTest`：新增 `errorFrameFailsInsteadOfCorruptingTerm`；
  `unregisterGroupStopsRoutingToNode` / `closeEndpointStillAllowsMajorityCommit`
  改为断言“多数派提交 + term 健全”（commitIndex ≥ 0 语义）；
- `MetadataNetworkRaftExtendedTest` / `RaftNodeSnapshotIntegrationTest`：
  测试竞态稳定化（状态轮询 / propose leader 重试）；
- 本 ADR；CHANGELOG。
