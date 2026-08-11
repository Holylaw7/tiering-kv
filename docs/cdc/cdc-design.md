# CDC 设计文档

Phase 26 · ADR-0105

## 1. 架构

```text
Raft Apply → CDCProducer → CdcLog（分段 + CRC32C）
                              ↓
                        CDCConsumer → sink
                              ↓
                        CDCCheckpoint（持久化 seq）
```

## 2. 事件

| 类型 | 载荷 |
| --- | --- |
| PUT | key / value / txnId / regionId |
| DELETE | key / deleted=true |
| TXN_COMMIT | key / value / txnId |
| REGION_MOVE | key / regionId |

## 3. exactly-once

- 生产者同步分配 seq 并追加（并发 emit 顺序保持）；
- 消费者从 `checkpoint+1` 消费，应用后推进检查点；
- 崩溃后从持久化检查点恢复，重复投递由 seq 幂等跳过。

## 4. 限制

- REGION_MOVE 与迁移联动为 Phase 27 深化项；
- 多消费者组（fan-out）未实现。
