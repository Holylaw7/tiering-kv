# 双向复制设计

Phase 28 · ADR-0114

## 1. 架构

```text
Region A（主） ⇄ BidirectionalPipeline ⇄ Region B（主）
                ├─ VersionVector（因果检测/环回抑制）
                ├─ LWW 冲突收敛（时间戳 + 节点优先级）
                └─ CRDT 原语（GCounter/GSet/OrSet）
```

## 2. 环回抑制

事件携带 origin 节点与版本；`VersionVector.seen(node, version)`
对已见事件直接抑制，杜绝环回风暴。

## 3. 冲突策略

- 默认 LWW：时间戳大者胜，同时间戳按节点名；
- CRDT 可配置：GCounter（只增计数）、GSet（只增集合）、OrSet
  （可增删集合，删除带唯一 tag）。

## 4. 使用

```java
BidirectionalPipeline pipeline = new BidirectionalPipeline(
        List.of(peerSink), "r1", 2_000);
pipeline.write(key, value).join();
pipeline.receive(key, value, "r2", version);
```

## 5. 限制

- LWW 受时钟偏差影响，节点优先级兜底；
- 单向路径（ReplicationPipeline）保持不变，零回退。
