# Bitcask 详细设计（Bitcask Design）

状态：草稿（Phase 4 细化）

## 范围

追加日志、内存索引、merge、崩溃恢复。

## 记录格式（ADR-0005 基线）

```text
[CRC32C][timestamp][key_len][value_len][type][key][value]
```

## 待定项

- 日志文件滚动阈值（默认 512MB 候选）；
- 索引项结构（fileId, offset, size）与内存占用控制；
- merge 触发的无效数据占比阈值。
