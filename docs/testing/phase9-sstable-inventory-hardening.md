# Phase 9：SSTable Inventory Hardening

- 日期：2026-11-05
- 提交：`384d7e5`（`feat(tiering): SSTable 清单加固（TD-100）`）
- 基线：`78d3896`（Phase 8）
- 范围：只加固现有 SSTable 清单（正确性 / 规模 / 并发 / 可用性），**不新增**生命周期字段、
  不改 SSTable 持久化格式、不改 WAL/MemTable/Compaction 逻辑、不改 Redis 协议语义。

## 0. 结论速览

| 验收项 | 结果 |
| --- | --- |
| Key Comparator 语义 | **字节字典序（`Arrays.compareUnsigned`）已核实并在协议中声明** |
| 二进制 / 空 / 长 Key | 通过（转义文本 + 无损 Base64 双通道） |
| 快照一致性（Flush + Compaction 并发） | 通过（3 读线程 × 1.5s，无异常，逐行之和恒等于总计） |
| 规模 1/10/100/1000 张表 | 通过（真实引擎夹具，渲染 ≤ 2.2ms） |
| 多实例隔离 | 通过（5 项检查 × 2 实例，互不相同） |
| API 权限与失败状态 | 通过（401 / 404 / 502 三态明确） |
| 前端排序 / 分页 / 复制 / 三态 | 通过（UI 回归 12/12 + 交互脚本） |

## 1. 项目审查（先审查再改）

### 1.1 键比较器

`storage/cold/Keys#compare` → `Arrays.compareUnsigned`，即**无符号字节字典序**，与 MemTable 一致。
因此 `tier:1000000` **小于** `tier:999999`（第 5 个字节 `'1'` < `'9'`），
界面上 `tier:1000000 … tier:999999` 看起来"反了"，实际是正确的。

**审查结论**：语义无误，但此前**未在协议里声明**，前端也没有任何提示，容易被误读为 bug。

### 1.2 条目统计口径

| 概念 | 来源 | 语义 |
| --- | --- | --- |
| `sstable.entryCount` | 写入时计数 | 表内条目数，**含历史版本与墓碑** |
| `sstable_total_entries` | 各表 entryCount 之和 | 冷层条目上界，**不是去重键数** |
| `tiering_hot_keys` | MemTable 存活条目 | 精确 |
| `tiering_cold_entries` | 同 total_entries | 上界 |
| 去重键数 | `DBSIZE` / `total_keys` | 精确，但冷层变更后首次需要全量迭代 |

**审查结论**：口径本来是对的，风险在于"被误用"。已在接口文档与抽屉文案中固定说明，
并在本阶段用测试固定该语义（见 §4）。

### 1.3 快照与锁

`ColdStorageEngine.tablesSnapshot()` = `synchronized(lock) { List.copyOf(tables) }`：
- 拷贝发生在锁内，**表数量级 O(n)，n 为个位数**；
- `SSTableMeta` 是不可变 record，拷贝后不会被并发修改；
- Base64 / 文本拼接发生在 `SstableInfoRenderer.render()`，**在锁外**（Main 先取快照再渲染）；
- `installCompaction` 在同一把锁内整体替换 `tables`，因此快照要么是合并前、要么是合并后，不存在中间态。

**审查结论**：设计正确。本阶段用并发测试把它固定为可回归的断言，而不是只依赖推理。

## 2. 实际缺陷与最小修复

| # | 缺陷 | 修复 |
| --- | --- | --- |
| 1 | 前端 `openTables()` 失败时未清空 `tableList`，会展示**上一个实例**的表 | 请求开始即清空列表与汇总；失败只留错误条 |
| 2 | 键序语义未声明，数字型 Key 易被误读 | 内核输出 `sstable_key_order:bytewise`；DTO 增 `keyOrder`；界面显示 `BYTEWISE ORDER` 徽标 + 提示 |
| 3 | 二进制 Key 只能 UTF-8 有损解码 | 内核增 `first_text/last_text`（`\xNN` 转义，64 字节截断）；DTO 增 `firstKeyBase64/lastKeyBase64` 供复制 |

其余代码**未改动**（没有为了增加提交量而调整）。

## 3. 性能实测

### 3.1 内核清单（真实 `ColdStorageEngine`，非伪造数据）

`SstableInventoryScaleTest`：把 `compactionThreshold` 调到 100000，用 `writeTable` 真实落表。

| 表数 | 快照（`List.copyOf`） | 渲染（文本 + Base64） | 输出大小 |
| --- | --- | --- | --- |
| 1 | 7 µs | 1,949 µs（首次含 JIT） | 191 B |
| 10 | 7 µs | 96 µs | 953 B |
| 100 | 9 µs | 573 µs | 8,797 B |
| 1,000 | 52 µs | 2,236 µs | 97,003 B |

结论：**线性、微秒级**。1000 张表的最坏渲染约 2.2ms，且发生在锁外。

### 3.2 控制面解析与序列化（1000 行真实格式输入）

| 指标 | 实测 |
| --- | --- |
| `TieringTableService.list` 解析耗时 | **9.1 ms** |
| 返回 JSON 大小 | **145,925 B** |
| 内核文本大小 | 121,954 B |

结论：1000 张表下响应体约 143 KB，仍在常规 JSON 范围；解析 9ms 可接受。

### 3.3 前端

7 张表（当前引擎在一轮运行中可达到的上限）下：
- 抽屉打开到渲染完成 < 1.8s（含一次 `INFO sstables` 往返）；
- 排序为纯前端计算（7 行 → < 1ms），按"大小"升序首行为最小表、降序首行为最大表，实测符合预期。

## 4. 新增测试清单

| 文件 | 用例数 | 覆盖 |
| --- | --- | --- |
| `SstableInfoRendererTest`（扩充） | 7 | 基础渲染、**字节序声明**、`\xNN` 转义、长键截断、空键、空引擎、按 id 升序 |
| `SstableInventoryScaleTest`（新） | 2 | 1/10/100/1000 张表的成本与自洽性；Flush+Compaction 并发下快照一致性 |
| `TieringTableServiceTest`（扩充） | 7 | 正常解析、`keyOrder`、旧内核无 `*_text` 回退、二进制边界、1000 行规模、空冷层、错误映射 |
| `SystemTablesApiTest`（新） | 4 | 匿名 401、ADMIN/VIEWER 可读、未知实例 404、离线实例 502/50201 |

全量结果：

- 内核：**15,010 通过 / 0 失败 / 13 跳过**（`mvn test -Dsurefire.excludedGroups=benchmark`）
- 控制面：**88 通过 / 0 失败**（19 个测试类）
- 前端：`npm run build` ✅、`vue-tsc --noEmit` ✅、`ui-regression.mjs` **12/12**

## 5. API 契约变化

### 5.1 内核 `INFO sstables`（只增字段，不删不改）

```
sstable_key_order:bytewise          # 新增：比较器语义（当前恒为 bytewise）
sstable:17|keys=…|bytes=…|first_b64=…|last_b64=…|first_text=tier:0|last_text=tier:999999
                                    #                                       ^^^^^^^^^^^^ 新增
```

- `first_text/last_text`：可打印 ASCII 原样，其余转义为 `\xNN`，超过 64 字节截断为 `…`；
- 旧控制面忽略新字段即可，**向后兼容**。

### 5.2 控制面 `GET /api/v1/system/tables`

```jsonc
{
  "keyOrder": "bytewise",            // 新增
  "count": 7,
  "totalBytes": 309847210,
  "totalEntries": 3421940,
  "tables": [
    { "id": 25, "keys": 2217948, "bytes": 206033920,
      "firstKey": "perf:1793885536019:0", "lastKey": "tier:999999",
      "firstKeyBase64": "cGVyZjoxNzkzODg1NTM2MDE5OjA=",   // 新增
      "lastKeyBase64": "dGllcjo5OTk5OTk=" }               // 新增
  ]
}
```

- 全部为**新增字段**，现有字段语义不变；
- `docs/api/openapi.json` 与 `console-web/src/api/schema.d.ts` 已重新生成，契约漂移测试通过。

## 6. 并发验证方法（可复跑）

`SstableInventoryScaleTest#snapshotIsConsistentWhileFlushAndCompactionRun`：

```
写线程：循环 writeTable(...) + 每 5 次 compactAll()   （compactionThreshold=4，必然触发合并）
读线程 ×3：循环 tablesSnapshot() → render() → 校验
校验规则：sstable_count == 行数 &&
          sstable_total_bytes == Σ 行 bytes &&
          sstable_total_entries == Σ 行 keys
断言：无异常、渲染次数 > 10
```

该规则能捕获"撕裂快照"（行与总计来自不同时刻）与"引用已删除对象"两类问题。

## 7. 已知限制

1. **分页阈值 50 在当前引擎不可达**：Compaction 阈值为 8，正常运行下冷层表数 ≤ 8。
   分页是防御性设计（未来若调整阈值或引入分层合并才用得上），未做端到端大数据验证。
2. **1000 张表的端到端 UI 未验证**：需要把 `compactionThreshold` 调到上千，
   属于引擎配置变更，超出本阶段范围；内核与控制面均已用真实数据覆盖。
3. **`first_text` 超过 64 字节会截断**为 `…`，完整内容需读取 `firstKeyBase64`。
4. **未提供表级时间戳/来源**（明确排除在范围外）。
5. 清单读取的锁开销随表数线性增长（1000 张表 52 µs），当前可忽略。

## 8. 复现命令

```powershell
# 内核：规模 + 并发
mvn -o -B '-Dtest=SstableInventoryScaleTest' test
# 控制面：解析 + 规模 + 失败状态
cd console-server; mvn -B '-Dtest=TieringTableServiceTest,SystemTablesApiTest' test
# 多实例隔离（需 6379/6380 与控制面在跑）
cd console-web; node scripts/phase9-inventory-isolation.mjs
# 抽屉交互（排序 / 复制 / 徽标）
node scripts/phase9-inventory-check.mjs
```

## 9. Git 记录

| 提交 | 说明 |
| --- | --- |
| `384d7e5` | `feat(tiering): SSTable 清单加固（TD-100）` |
| （本文档） | `docs(testing): Phase 9 验收报告` |

截图：`docs/screenshots/v2/28-sstable-hardening.png`。
