# 淘汰策略详细设计（Eviction Design）

状态：✅ 已实现（Phase 3，ADR-0004 / 0010 / 0011 / 0012）

## 1. 架构

```text
Command Layer
     │
     ▼
TrackingStorageEngine（StorageEngine 装饰器：产生 AccessEvent）
     │
     ▼
EvictionManager
     ├── EvictionPolicy（LFU 默认 / ARC 原型）
     ├── MemoryManager（超限检测）
     └── MigrationCallback（本阶段占位，Phase 4/6 接冷存储）
```

MemTable 核心结构不变；Command 层只依赖 StorageEngine。

## 2. 事件模型

`AccessEvent{key, operation(GET/PUT/DELETE/EVICT), timestamp, sizeBytes}`：

- GET / EXISTS：读事件（不携带 size）；
- PUT / UPDATE：写事件（携带 `KeyValueEntry.sizeOf`）；
- DELETE：用户删除，策略侧移除热度状态；
- EVICT：淘汰删除，ARC 移入 ghost、LFU 移除索引。

## 3. LFU（默认）

- HotnessTracker：ConcurrentHashMap<key, HotnessEntry>，按 key 原子更新；
- FrequencyCounter：频率 + 惰性周期衰减（ADR-0011）；
- 候选索引：TreeSet<IndexedKey>（频率 ↑ / lastAccess ↑ / key ↑），O(logN)
  更新、O(1) 取最小。

## 4. ARC（原型，ADR-0012）

- T1/T2（LinkedHashMap，插入序 = LRU）+ B1/B2 ghost；
- p 自适应：B1 命中 p↑，B2 命中 p↓；
- EVICT 事件把 T1 淘汰键移入 B1、T2 淘汰键移入 B2；
- 淘汰候选：|T1| > p 时取 T1 LRU，否则取 T2 LRU。

## 5. EvictionManager

```text
PUT → MemTable → MemoryManager（used > max）
   → selectCandidate → 存活校验
   → MigrationCallback.migrate(entry)
   → MemTable.delete(key) → EVICT 事件
```

- 每轮最多 `maxEvictionsPerCycle` 次，防 tombstone 边界死循环；
- 过期/失效候选：清理策略状态后跳过；
- 本阶段 MigrationCallback 为占位（无盘迁移 = 数据丢弃并计数），
  Phase 4/6 接 WAL / Bitcask / LSM；
- **淘汰删除 = 物理移除**（`MemTable.removePhysical`，内存回收优先）；
  用户 DEL 仍走 tombstone（WAL / Snapshot / LSM 需要删除历史）。

## 6. 得分（ADR-0010 附录）

- LFU：`score = frequency`，排序 `frequency ↑ → lastAccess ↑ → key ↑`；
- ARC：候选 = 队列 LRU 位置（不计算数值分数）。
