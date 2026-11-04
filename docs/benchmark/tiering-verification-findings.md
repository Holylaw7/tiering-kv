# 冷热分层端到端验证：发现 TD-086（冷层只写不读）

- 日期：2026-10-28
- 环境：JDK 21 · 单机（`-Xmx2g`）· 引擎 `memory.limit=128MB`（`-Dtieringkv.memory.heap-ratio` 默认）
- 脚本：`scripts/verify-tiering.mjs`（确定性键名，可复现）

## 1. 复现步骤

```bash
# 1) 以 128MB 引擎上限启动（端口 6380）
java -Xmx2g -cp target/tiering-kv-5.0.3.jar io.tieringkv.Main   # config/application.yaml: port=6380, memory.limit=134217728
# 2) 写入 60 万个 256B 的确定性键，随后全量读回
node scripts/verify-tiering.mjs --port 6380 --keys 600000 --value 256
```

## 2. 实测结果

| 指标 | 数值 |
| --- | --- |
| 写入 | 600,000 / 600,000 成功（141,309 ops/s） |
| **读回命中** | **311,539 / 600,000 = 51.92%** |
| 读回丢失 | **288,461 个键返回 nil** |
| 值不一致 | 0（能读到的键内容全部正确） |
| `DBSIZE` | 311,539（**恰好等于命中数**） |
| 引擎 `used_memory` | 103.7 MB / 128 MB |
| 冷层落盘 | `data/cold` 两个 SSTable 共 **199.8 MB**；`data/wal` 390.9 MB |
| 读延迟 | P50 0.125 ms / P95 0.308 ms / P99 0.44 ms |
| 早写入段（冷候选） | P50 0.118 ms / P95 0.306 ms |
| 晚写入段（热候选） | P50 0.139 ms / P95 0.320 ms |

## 3. 结论：迁移发生了，但迁移后的数据读不回来

直接探测：

```
GET verify:0        -> (nil)
GET verify:1000     -> (nil)
GET verify:5000     -> (nil)
GET verify:100000   -> (nil)
GET verify:599999   -> "v:599999:xxx..."   # 最近写入，仍在内存
```

代码证据：全仓库 `cold.put(...)` 出现在 `MigrationScheduler`、`ColdMigration`，
而 **`cold.get(...)` 在 `src/main` 中不存在**——主读路径
（`TrackingStorageEngine → HotKeyStorageEngine → WALStorageEngine → MemTable`）
从不查询冷层。

因此当前语义是：

| 行为 | 实现情况 |
| --- | --- |
| 内存超水位 → 迁移数据到 SSTable | ✅ 已实现（199.8MB 落盘） |
| 迁移后释放内存 | ✅ 已实现 |
| **迁移后按键读回** | ❌ **未实现**（`GET` 永远只查内存） |
| `DBSIZE` / `SCAN` 覆盖冷层 | ❌ 只统计热层 |

## 4. 影响

1. **本质是"超配即丢数据"**，而非"冷热分层"：本次 60 万键只保住 52%。
2. 与 Redis 对比实验中"3M 键 GET 57,787 rps"的数字**不能代表冷读**——那批请求里
   约一半是直接 miss，真实冷读能力未测。
3. 若答辩被问"冷数据还能读吗"，当前答案是不能——这是必须处置的硬伤。

## 5. 处置选项（待决策）

| 方案 | 内容 | 工作量 | 风险 |
| --- | --- | --- | --- |
| A 记录为已知限制 | 报告如实写"冷层只写不读"，不修 | 0.5h | 答辩硬伤 |
| B 最小修复 | `TieringStorageEngine` 覆盖 `get`：内存 miss 时回查 `cold.get`，命中即返回（可选回填） | 2–3h（含测试与复测） | 读路径多一次分支；冷读延迟需实测 |
| C 完整修复 | B + `DBSIZE`/`SCAN` 覆盖冷层 + 冷层删除语义 | 6–8h | 触及扫描/统计语义，回归面大 |

建议 **B**：先把"读得回来"补上，统计口径的差异写进文档（并列为后续项）。

## 6. 复现产物

- 脚本：`scripts/verify-tiering.mjs`
- 原始结果：`C:\\Users\\Tang\\AppData\\Local\\Temp\\tk-verify\\result.json`（本地，未入库）
