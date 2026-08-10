# ADR-0058: Multi-Raft Design

## Status

Accepted

## Context

Phase 16 需要支持多个 Region 各自独立运行 Raft 组（Multi-Raft）。
要求：多个 RaftNode 并行、共享 RPC 传输（单端口）、日志/状态/快照
按组隔离、组故障互不影响。Raft Consensus 语义保持不变。

## Decision

- `cluster.multiraft`：
  - `MultiRaftNode`：单进程内多 Raft 宿主，注册/启动/销毁按组隔离；
  - `RaftGroupManager`：按 Region 创建/销毁组，每组独立
    `ReplicatedStorageEngine`（本地存储 + 复制适配器）；
- `cluster.rpc`：
  - `MultiRaftEndpoint`：共享单端口 RpcServer + RpcClient；请求 payload
    前缀 `[groupId]`，服务端按组分发；响应保持原格式（requestId 关联），
    兼容既有 RPC 帧语义；
  - `MultiRaftTransport`：实现既有 `RaftTransport` 接口，每个组一个
    包装实例，RaftNode API 完全不变。
- 隔离边界：日志（MemoryRaftLog 或按组目录 FileRaftLog）、持久状态
  （按组目录）、快照（按组目录）均按组隔离；
- 调度：RaftNode 自带 scheduler/flushScheduler 线程，组间天然并行；
  组销毁只关闭该组 RaftNode。

## Alternatives

1. 每 Region 一个端口/传输：端口与连接数线性增长，否决。
2. 修改 Raft 消息协议增加组字段：破坏既有 API/协议兼容，否决。
3. 单 Raft 组 + 分片键：不具备 Region 级隔离与独立演进能力，否决。

## Consequences

优点：Region 级故障隔离；共享单端口降低运维成本；RaftNode/RaftTransport
接口零改动；支持组级持久化与快照。

缺点：端点内按组分发增加一次 payload 前缀编解码；组数量大时单端口成为
潜在瓶颈（可多端点横向扩展）。

风险：组间资源（线程/连接）共享，极端场景需配额（后续阶段）。

## Implementation

- `src/main/java/io/tieringkv/cluster/multiraft/`
- `src/main/java/io/tieringkv/cluster/rpc/MultiRaftEndpoint.java`
- `src/main/java/io/tieringkv/cluster/rpc/MultiRaftTransport.java`
- 测试：MultiRaftNodeTest / RaftGroupManagerTest / MultiRaftTransportTest
  （32 项，进程内 + 真实 TCP 单端口多组）。
