# 分布式架构（Distributed Architecture）

状态：✅ 原型完成（Phase 11）

## 1. 拓扑

```text
Client
  │
  ▼
Gateway（连接 + 路由 + 拓扑缓存）
  │
  ▼
Cluster Router（CRC16(key) % 16384 → slot → shard）
  │
  ▼
Metadata Service（shard → 节点组 → leader，Raft 化元数据）
  │
  ▼
Shard Leader → Raft Group（3 节点）
  │
  ▼
ReplicatedStorageEngine（适配器，不改存储核心）
  │
  ▼
TieringStorageEngine（Phase 1–10 单机引擎）
```

## 2. 节点类型

- **Gateway**：客户端连接、请求路由、拓扑缓存；
- **Metadata**：维护 `shard → node group → leader`（ADR-0036）；
- **Storage**：复用 TieringStorageEngine；写入经 Raft 复制（ADR-0037）。

## 3. 数据路径

```text
写：Client → Router(slot) → Leader → Raft append → 多数派 ack
    → apply 本地存储 → 应答
读：Leader/Replica 本地读（原型语义，强一致读留后续）
故障：Leader 崩溃 → 选举（<5s）→ 元数据更新 → 客户端重路由
```

## 4. 分片

16384 hash slots（Redis Cluster 风格，ADR-0035）；slot 表可重映射
（rebalance 友好）。

## 5. 原型边界

- 进程内传输（真实 Raft 语义，网络传输留后续）；
- Raft 日志内存存储（持久化 Raft log 留后续）；
- 单分片多副本模型（多分片拓扑由元数据支持）。
