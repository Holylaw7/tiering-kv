# Tiering-KV 总体架构

状态：Accepted（对应 ADR-0001 / ADR-0002 / ADR-0003）

## 1. 架构目标

- 分层清晰、接口优先、依赖单向；
- 支撑 1k–100k 并发连接与多核扩展；
- 内存 + 磁盘冷热分层，异步迁移不阻塞请求路径；
- 引擎可替换（Bitcask / LSM-Tree），上层无感知。

## 2. 分层视图

```text
Client
  │
  ▼
RESP Protocol
  │
  ▼
Network Layer
  │
  ▼
Command Engine
  │
  ▼
Memory Tier (MemTable)
  │
  ▼
Hotness Manager
  │
  ▼
Cold Storage
  │
  ▼
Bitcask / LSM Tree
```

横切模块：WAL、Scheduler、Metrics、Eviction（LFU/ARC）、Compaction、
Bloom Filter、Memory Pool。

## 3. 模块职责

| 模块 | 职责 | 主要阶段 |
| --- | --- | --- |
| network | Netty 事件循环、连接管理、背压 | Phase 1/7 |
| protocol | RESP 编解码与错误处理 | Phase 1 |
| command | 命令分发、按 key 分片顺序执行 | Phase 1/2 |
| memory | MemTable（分段哈希）、TTL、内存配额 | Phase 2 |
| cache / eviction | LFU / ARC 热度管理 | Phase 3 |
| storage | StorageEngine SPI（Bitcask / LSM） | Phase 4/5 |
| wal | 预写日志与崩溃恢复 | Phase 4 |
| sstable | SSTable 读写（LSM 文件格式） | Phase 5 |
| compaction | 后台合并与层级压缩 | Phase 4/5 |
| scheduler | 异步冷热迁移调度 | Phase 6 |
| metrics | 延迟、队列、竞争、放大系数观测 | Phase 3+ |
| benchmark | 压测与基准 | Phase 9 |

## 4. 关键路径

**写路径：**

```text
Client → RESP → Command → WAL → MemTable →（异步）→ Cold Storage
```

**读路径：**

```text
Client → RESP → Command → MemTable 命中 / Cold Storage 读取并升热
```

**迁移路径：**

```text
Hotness Manager 采样 → Scheduler 异步迁移 → 索引与层级更新
```

## 5. 关键决策映射

| 主题 | 决策 | 来源 |
| --- | --- | --- |
| 技术栈与模块边界 | Java 17 + Maven，包级模块单向依赖 | ADR-0001 |
| 存储引擎 | StorageEngine SPI；Bitcask 先行、LSM 演进 | ADR-0002 |
| 并发模型 | Netty 事件循环 + key 分片 + 分段锁 + 异步迁移 | ADR-0003 |

## 6. 演进路线

按 [ROADMAP](../ROADMAP.md) 的 Phase 0–10 推进；任何架构级变更必须先更新
对应 ADR，再进入实现。
