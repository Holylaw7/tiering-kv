# G0-5C 性能资格验证：证据（NOT QUALIFIED）

- 判定：**NOT QUALIFIED（GET/SET 反向信号经三轮复测未复现，见 §6）**
- After：`807b653`（F1–F4 全部合入）；Before：`7bbf14c`（G0-5 改造前）
- 环境：Windows 11 / Microsoft OpenJDK 21.0.12 / 默认 JVM 参数

## 1. 为什么是 NOT QUALIFIED

| 缺口 | 说明 |
| --- | --- |
| 轮次不足 | 每项仅 **1 轮**，未做 3 轮采样与中位数 |
| 未覆盖冷层 | 数据 20,000 键全部驻留内存，**F1 的 10 万冷键场景未构造** |
| 未测 F2/F3/F4 | Span 写入/淘汰、后台刷新耗时、record 吞吐与分配量均未采集 |
| 环境未隔离 | 开发机，其他进程可能干扰；未记录 GC 与 RSS |
| 数据目录不同 | Before 使用全新数据目录，After 复用旧目录（同 2 万键，但冷层状态不同） |

> 按你定的口径：证据不足不得判 PASS，因此这里直接判 NOT QUALIFIED，不把冒烟数据包装成结论。

## 2. 冒烟数据（每项 1 轮，仅作参照）

工作负载：20,000 键 × 100 B；预热后 200 次 INFO、2,000 次 GET + 2,000 次 SET 串行。

| 指标 | Before (`7bbf14c`) | After (`807b653`) | 变化 |
| --- | --- | --- | --- |
| INFO p50 (ms) | 0.365 | 0.412 | +12.9% |
| INFO p95 (ms) | 0.625 | 0.641 | +2.6% |
| INFO p99 (ms) | 0.825 | 0.756 | −8.4% |
| INFO max (ms) | 6.582 | 0.797 | −87.9% |
| GET p50 (ms) | 0.125 | 0.168 | +34.4% |
| GET p99 (ms) | 0.301 | 0.326 | +8.3% |
| SET p50 (ms) | 0.130 | 0.170 | +30.8% |
| SET p99 (ms) | 0.357 | 0.368 | +3.1% |

原始数据：`raw/before-smoke-run.json`、`raw/after-smoke-run.json`。

## 3. 必须正视的观察

1. **GET/SET p50 在 After 版本上反而更高（+30% 左右）**。单轮数据不足以定性，但这是**潜在回归信号**，
   不能因为"四项改造都是删代码/加缓存"就假设不会变慢；
2. INFO max 从 6.58 ms 降到 0.80 ms，与"INFO 不再触发全量扫描/复制"的方向一致，但同样是单轮；
3. p50 的差异可能来自数据目录不同（冷层状态不同）与预热不足，**必须用同数据、多轮实验澄清**。

## 4. 复现方式

```powershell
git worktree add ..\tiering-kv-before 7bbf14c
cd ..\tiering-kv-before; mvn -q -DskipTests package
# 分别以各自 jar 启动内核（同一数据目录、同端口）
node docs/benchmarks/g0-5/scripts/bench.mjs --port 6379 --keys 20000 --value 100 --info 200 --ops 2000 --label before-run3
```

## 5. 结论

**NOT QUALIFIED**。四项改造的结构性目标已由 ADR 与测试证明，但性能资格**未通过**；
且冒烟数据出现了 GET/SET p50 变慢的迹象，需要优先排查，而不是先宣布优化成功。
## 6. G0-5C-1 复测结果（GET/SET 反向信号）

### 6.1 基准有效性审计（`bench.mjs` → `bench2.mjs`）

| 审计项 | 结论 |
| --- | --- |
| 是否等待真实 RESP 响应 | 是，每请求一次 `await`，按响应解析后计时 |
| 连接数 / Pipeline | 单连接、串行、无 pipeline；两版本完全一致 |
| 预热与采样分离 | `bench2.mjs` 预热 6,000 次 GET + 100 次 INFO，不进采样 |
| 百分位 | 最近秩法（nearest-rank），单位 ms，两版本同一函数 |
| 是否混入连接/seed | 连接与 seed 在计时之外；`bench2` 把 seed 与预热放在采样前 |
| **客户端开销** | **显著**：RTT 约 0.08 ms，单连接串行下客户端事件循环占相当比例。绝对延迟不能当作服务端能力，但两版本可比 |

审计发现并修正的问题：原 `bench.mjs` 把 seed 后立即测量且未分相，已改为 `bench2.mjs`；
**未修改任何生产代码**。

### 6.2 环境与数据隔离

- Before / After 各自使用独立数据目录（`.bench/before`、`.bench/after`），**每轮开始前删除 `data` 目录**重新初始化；
- 同机、同 JDK 21、默认 JVM 参数 + `-Xlog:gc` 记录 GC；
- 交错顺序：`B1 → A1 → A2 → B2 → B3 → A3`；
- 每轮服务端进程独立启动，RSS 采样 284–288 MB（两版本接近）。

### 6.3 三轮对比（p50，单位 ms）

| 轮次 | 版本 | GET p50 | SET p50 | INFO p50 |
| --- | --- | --- | --- | --- |
| 1 | Before | 0.0765 | 0.0848 | 0.2848 |
| 1 | After | 0.0752 | 0.0827 | 0.2753 |
| 2 | After | 0.0779 | 0.0790 | 0.2733 |
| 2 | Before | 0.0803 | 0.0842 | 0.3106 |
| 3 | Before | 0.0779 | 0.0910 | 0.2928 |
| 3 | After | 0.0849 | 0.0863 | 0.2861 |
| **中位数** | Before | **0.0779** | **0.0848** | **0.2928** |
| **中位数** | After | **0.0779** | **0.0827** | **0.2753** |

p95/p99（中位数）：GET Before 0.1803/0.2430、After 0.1771/0.2441；
SET Before 0.1895/0.2567、After 0.1798/0.2565；INFO p99 Before 0.6108、After 0.5828。

### 6.4 结论

1. **此前 +30% 的 GET/SET p50 差异未能复现**。它更可能来自：单轮、数据目录不同（Before 全新 / After 复用）、预热不足与机器噪声；
2. 三轮中位数：GET 完全相同（0.0779 vs 0.0779），SET After 略低 2.5%，INFO After 低约 6%——**均在波动范围内，不构成回归**；
3. 但**轮间波动明显**（After GET p50 从 0.0752 到 0.0849，约 ±6%），说明本机作为基准环境噪声较大，
   后续任何小于 10% 的差异都不应被解释为优化或回归；
4. F3 后台刷新线程的耗时/频率**无法在不改代码的前提下读取**（未暴露指标），本轮只记录了"刷新周期内未见 GET 延迟周期性抬高"，属间接观察，不作为结论；
5. GC：每轮 4 行日志（启动期），采样期间未见 Full GC；未统计停顿明细。

### 6.5 判定

- 就"GET/SET 是否出现可复现回归"这一问题：**未复现，暂不认定回归**；
- 就 G0-5C 整体资格：**仍为 NOT QUALIFIED**——冷键场景、F2/F3/F4 专项、环境隔离均未完成；
- 冒烟数据与本次复测数据全部保留在 `raw/`，不删除不利结果。
## 7. F1 冷层专项：资格检查未通过（NOT QUALIFIED）

尝试构造 100,000 个冷层逻辑 Key（`docs/benchmarks/g0-5/scripts/f1-cold.mjs`），配置 `memory.limit=4 MB`，
写入 100,000 × 40 B，等待 flush 与迁移稳定。

### 实测资格数据（After `807b653`）

```text
coldSstableCount = 0      ← 冷层没有表
flushCount       = 3      ← 发生了 3 次 flush
coldDiskBytes    = 0
dbsize           = 100000
```

### 结论

- **资格检查不通过**：`coldSstableCount = 0` 且 `coldDiskBytes = 0`，100,000 个 Key 并没有落进冷层；
  它们仍在内存/WAL 路径上（`DBSIZE` 读到 100000 只说明逻辑键在，不说明在冷层）；
- 按任务书要求，**该轮数据作废，没有用于对比**；INFO/DBSIZE 采样虽已跑出（INFO p50 0.24–0.29 ms），
  但**不具备 F1 解释力**，不写入结论；
- 未通过的原因（假设，待验证）：flush 只把 MemTable 写成"热层磁盘表"，冷层表要由迁移写入，
  而迁移由 `EvictionManager` 在内存临界水位时触发；4 MB 限额下 flush 持续把内存压低，
  从未触及临界水位，因此没有迁移发生。

### 下一步（需要额外构造实验）

1. 提高 `memory.limit`（例如 64 MB）并写入 ≥ 水位的数据，使内存越过临界水位触发 `EvictionManager` 迁移；
2. 或降低 `tiering.critical-watermark`（如 0.10）让小数据量也能触发迁移；
3. 构造后先验 `tiering_cold_sstable_count > 0` 且 `tiering_cold_disk_usage_bytes > 0`，再开始采样；
4. 启动成本与 INFO/DBSIZE 采样脚本已就绪，可在资格通过后直接复用。

**F1 性能资格：NOT QUALIFIED（未取得冷层数据）。**
## 8. F1 冷层构造：64 MB 方案同样未触发（G0-5C-2A）

### 8.1 配置与数据

`memory.limit = 64 MB`，默认 `tiering` 水位（70/85/95），分两阶段写入：

- 阶段一：100,000 × 800 B（批量 1,000），耗时约 2 s；
- 阶段二（突发）：追加 200,000 × 800 B（批量 20,000）。

### 8.2 观测（阶段一的时间线，摘自 `raw/f1/f1a-64mb.json`）

| 已写键 | memtable | flush | cold 表 | migration 成功 |
| --- | --- | --- | --- | --- |
| 11,000 | 9.2 MB | 0 | 0 | 0 |
| 51,000 | 42.5 MB | 0 | 0 | 0 |
| 71,000 | **59.2 MB** | 0 | 0 | 0 |
| 81,000 | 12.1 MB | **1** | 0 | 0 |
| 100,000 | 20.5 MB | 1 | 0 | 0 |

阶段二结束后终态：`mem=30.2 MB`、`flush=4`、`coldTables=0`、`coldBytes=0`、`migOk=0`、`migFail=0`、`DBSIZE=100,000+`（阶段二前缀不同）。

### 8.3 触发机制分析（代码证据）

- 驱逐入口：`TrackingStorageEngine:35` 每次写后调用 `evictionManager.maybeEvict()`；
- 触发条件：`EvictionManager:106` → `memoryManager.isOverLimit()`；
- 判据：`MemoryManager:101-107` → **`usedBytes > maxBytes`**（或堆占用超过 -Xmx 的 90%）；
- 抑制因素：内存达到 **85% 高水位**时 `FlushScheduler` 先触发 flush，把 memtable 清空，
  因此 `usedBytes` 在触及 100% 之前就被压回去 —— 观测到 59.2 MB 后立刻掉到 12.1 MB。

**结论：在默认水位 + 纯 SET 负载下，flush 会先于驱逐发生，`EvictionManager` 永远不会被触发，冷层永远不会产生数据。**
这不是配置不够大，而是两条路径的触发顺序决定的。

### 8.4 资格判定

| 条件 | 结果 |
| --- | --- |
| 冷层 SSTable > 0 | ❌ 0 |
| 冷层磁盘占用 > 0 | ❌ 0 |
| 100,000 逻辑 Key 可读 | ✅ DBSIZE=100,000，抽样 200 个 GET 值长度正确 |
| 证明 EvictionManager 触发过迁移 | ❌ `migOk=0`、`migPending=0` |
| flush/migration 稳定 | ✅ flush=4 后不再增长 |

**F1 冷层资格：NOT QUALIFIED（第二次尝试）。** 两轮构造脚本（`f1-construct.mjs`、`f1-burst.mjs`）与原始数据均已保留。

### 8.5 下一步（需要先决策，不宜继续盲试）

| 方案 | 代价 | 说明 |
| --- | --- | --- |
| A. 关闭 flush（把 high-watermark 设为 ≥1.0） | 改变默认行为 | 让内存真正越过 limit，由驱逐接管；但测的已不是默认配置 |
| B. 单条写入超大 value（如 8 MB） | 仍是正常写入路径 | 单次写入即可让 `usedBytes` 跃过 limit，可能先于 flush 触发驱逐 |
| C. 用测试代码直接调用 `EvictionManager.maybeEvict()` | 非端到端 | 只能证明迁移链路可用，不能证明"正常路径会产生冷层" |

> 我倾向 B：它保持默认水位与默认 flush 配置，只是把写入粒度调大，最接近"真实大对象负载"。
> 但这需要你确认——因为它是"用负载形状去适配触发条件"，和 A 一样有解释力上的代价。
