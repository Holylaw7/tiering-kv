# G0-5C 性能资格验证：证据（NOT QUALIFIED）

- 判定：**NOT QUALIFIED**
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
