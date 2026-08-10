# ADR-0041: Distributed RPC Design

## Status

Accepted

## Context

Phase 11 的 Raft 节点通过 Java 对象直接调用互相通信（进程内传输）。
生产环境节点分布在不同进程/机器，必须替换为网络 RPC。

## Problem

- 需要传输三类 Raft 消息：AppendEntries / RequestVote / InstallSnapshot；
- 需要请求-响应关联（correlation）、超时、重试、连接复用；
- 需要二进制协议，避免 Java 序列化（与 ADR-0015 一致）；
- 需要保持现有测试的进程内路径可用（不回退性能）。

## Options

1. **HTTP/JSON**：开发快，但编解码开销大、连接管理弱，被否决；
2. **gRPC/protobuf**：工程标准，但引入外部代码生成与依赖，不符合
   "从零实现"约束；
3. **Netty TCP + 自定义二进制协议**（选定）：轻量、可控、复用现有
   Netty 依赖。

## Decision

采用 **Netty TCP RPC**：

```text
RaftTransport
  ├── LocalRaftTransport  （Phase 11 进程内路径，测试/回退）
  └── NettyRaftTransport  （生产路径）
        ├── RpcClient（连接复用 + 请求关联 + 超时重试）
        └── RpcServer（解码 → 本地 RaftNode.receive → 响应）
```

1. 线协议：`LENGTH(4B) | RpcFrame`；
2. `RpcFrame = REQUEST_ID(8B) | TYPE(1B) | PAYLOAD_LENGTH(4B) | PAYLOAD`；
3. 类型：`APPEND_ENTRIES / REQUEST_VOTE / INSTALL_SNAPSHOT /
   APPEND_ENTRIES_RESPONSE / REQUEST_VOTE_RESPONSE / INSTALL_SNAPSHOT_RESPONSE`；
4. payload 为版本化二进制编码（含 term/index/entries/CRC），不使用
   Java 序列化；
5. `RpcClient` 按目标地址维护连接，请求超时默认 3s，可重试幂等
   RequestVote/AppendEntries；`RequestId` 关联响应与 CompletableFuture；
6. `RaftTransport` 抽象使 RaftNode 不感知传输细节：本地传输仍用于
   单元测试，生产使用 Netty 传输。

## Consequences

**优点：** 真实网络语义（超时/重连）、连接复用、协议可控、与现有测试兼容；
**缺点：** 自定义协议需自行维护兼容性（MAGIC + VERSION）；
**风险：** 网络分区与重复消息 → Raft term/幂等校验兜底（ADR-0038）。

## Future Evolution

- 消息批量（pipeline AppendEntries）；
- TLS + 认证；
- 多路复用连接池与背压。
