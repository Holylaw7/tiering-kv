# TSO 集群化（ADR-0216）

## 背景

全局时间戳服务需要：批量分配、单调推进、重启恢复不回退，为
resolved-ts / 事务协调器提供单调时钟。

## 设计

```text
allocate(batch) → [start, end]   （原子推进分配游标）
watermark     → 已分配最大值
restore(wm)   → watermark = max(wm, watermark)
                分配游标 = max(游标, watermark + 1)   ← 关键修复
```

恢复语义：只允许推进到更高水位；恢复后新分配严格大于已持久化水位，
避免重启后出现低于恢复水位的逻辑时间戳。

## 验收

- 分配矩阵：批量 1–120 × 轮数 1–10（76 项展开）；
- 单调性：单线程 + 并发分配；
- 恢复不回退：多轮 restore + 新分配越过水位；
- 非法入参：batch<1 / watermark<0 拒绝。
