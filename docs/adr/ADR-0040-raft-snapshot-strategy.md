# ADR-0040: Raft Snapshot Strategy

## Status

Accepted

## Context

Phase 11 的 Raft 日志只增不减。长时间运行的 leader 会产生百万级日志条目，
重启恢复与 follower 追赶都会线性退化；真实 Raft（etcd / TiKV）通过
Snapshot 压缩日志。

## Problem

- 需要把已提交状态固化为快照，避免日志无限增长；
- 需要 `lastIncludedIndex/lastIncludedTerm + 状态机数据 + checksum`；
- 落后 follower 需要从 Snapshot 追赶（InstallSnapshot）；
- 快照损坏时不能误恢复。

## Options

1. **仅本地快照**：压缩本地日志，但落后节点仍要全量重放，被否决；
2. **本地快照 + InstallSnapshot 传输**（选定）：leader 对 `nextIndex <=
   lastIncludedIndex` 的 follower 直接下发快照；
3. **无快照，仅日志压缩**：丢失随机访问能力，被否决。

## Decision

采用 **SnapshotManager + InstallSnapshot**：

```text
log: 1 2 ... 90000 90001 ... 100000
                     ↑ snapshot(lastIncludedIndex=90000, term=T)
remaining log: 90001-100000
```

1. 快照格式（`SnapshotWriter/SnapshotReader`）：
   `MAGIC | VERSION | LAST_INCLUDED_INDEX(8B) | LAST_INCLUDED_TERM(8B)
   | STATE_LENGTH(4B) | STATE_DATA | CRC32C(4B)`；
2. 触发条件：日志条数超过阈值（默认 1024）且已提交；
3. 创建后：`truncateFrom(lastIncludedIndex + 1)` 删除旧日志，
   旧段可物理删除；
4. 恢复：先加载 Snapshot（校验 CRC）→ 恢复状态机 → 再重放剩余日志；
5. 落后 follower：leader 发送 `InstallSnapshot` RPC，follower 校验后
   应用快照、重置日志，再继续 AppendEntries；
6. `SnapshotManager` 负责创建/校验/恢复，RaftNode 提供状态机
   snapshot source/sink 回调（`Supplier<byte[]>` / `Consumer<byte[]>`）。

## Consequences

**优点：** 日志有界、重启恢复时间有界、落后节点快速追赶；
**缺点：** 快照创建有成本（状态序列化 + 写盘），需要后台异步执行；
**风险：** 快照传输大对象 → 校验 + 限流；损坏快照 → CRC 拒绝并回退
全量日志重放。

## Future Evolution

- 快照文件版本化 + 多代保留；
- 增量快照（基于版本差异）；
- 快照与 SSTable/Manifest 融合（直接以冷层表作为状态）。
