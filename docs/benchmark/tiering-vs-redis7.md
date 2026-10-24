# Tiering-KV 与 Redis 7 对比实测

- 日期：2026-10-24
- 目的：给出可复现的横向对比数据，并如实记录双方的能力边界
- 结论摘要：**吞吐为 Redis 的 1/3 左右，单键内存开销高出约 10 倍**；
  冷热分层确实会落盘（产出 237 MB SSTable），但在受约束堆下会出现
  **OOM 而非优雅降级**——列为后续修复项。

## 1. 测试环境与方法

| 项 | 值 |
| --- | --- |
| 宿主机 | Windows 11 · 20 逻辑核 · 31.6 GB 内存 · Docker 29.8（WSL2 后端） |
| 服务端 | 双方均为容器，同一 bridge 网络 `tkbench`，服务端只监听容器内 6379 |
| Tiering-KV | 镜像 `tiering-kv-console-tiering-kv:latest`（v5.0.0），`java -Xmx2g -cp /app/tiering-kv.jar io.tieringkv.Main` |
| Redis | `redis:7-alpine`，`redis-server --save '' --appendonly no` |
| 客户端 | `redis-benchmark`（redis:7-alpine 自带），与两端同网络 |
| 工作负载 | `-r` 指定键空间做随机键，`-d 64` 值 64 字节 |

**公平性说明**

- 同一台机器、同一客户端、同一网络，客户端与服务端之间的 Docker 网络
  开销对双方一致；
- Redis 关闭 RDB/AOF；Tiering-KV 保持默认 WAL（`fsync=EVERY_SEC`）——
  即 Tiering-KV 承担持久化成本而 Redis 不承担，因此写路径对比对
  Tiering-KV **偏保守**；
- `redis-benchmark` 默认单键（`-r 0`）会把所有请求压到同一个 key 分片，
  对 Tiering-KV 的 16 分片模型极不公平，故全部使用 `-r` 随机键空间。

复现命令（节选）：

```bash
docker network create tkbench
docker run -d --name bench-redis --network tkbench redis:7-alpine \
  redis-server --save '' --appendonly no
docker run -d --name bench-tk --network tkbench \
  -v <repo>/config:/app/config:ro \
  --entrypoint java tiering-kv-console-tiering-kv:latest \
  -Xmx2g -cp /app/tiering-kv.jar io.tieringkv.Main

docker run --rm --network tkbench redis:7-alpine redis-benchmark \
  -h bench-tk -p 6379 -n 200000 -c 50 -r 1000000 -t set,get -q
```

## 2. 吞吐与延迟（100 万键空间，P=1）

| 场景 | Tiering-KV | Redis 7 | 比值 |
| --- | --- | --- | --- |
| SET · c=1 | 10,347 rps / p50 0.079 ms | 21,834 / 0.039 ms | 0.47× |
| GET · c=1 | 11,148 / 0.079 ms | 23,641 / 0.039 ms | 0.47× |
| SET · c=50 | 59,701 / 0.431 ms | 165,975 / 0.159 ms | 0.36× |
| GET · c=50 | 63,512 / 0.423 ms | 161,160 / 0.159 ms | 0.39× |
| SET · c=200 | 53,060 / 1.871 ms | 143,541 / 0.695 ms | 0.37× |
| GET · c=200 | 55,300 / 1.799 ms | 152,827 / 0.655 ms | 0.36× |
| SET · P=16 | 161,290 / 4.391 ms | 777,202 / 0.887 ms | 0.21× |
| GET · P=16 | 227,273 / 3.191 ms | 925,926 / 0.767 ms | 0.25× |

观察：

1. 单连接下差距最小（约 2.1×），说明协议层与单请求路径没有数量级问题；
2. 并发升高后 Tiering-KV 的 p50 从 0.079 ms 涨到 1.87 ms，吞吐在
   c=200 时还略有回落（53k < 60k），瓶颈在分片执行与响应序列化，
   而不是 CPU 吃满；
3. 流水线场景差距最大（4–5×），Redis 的批量写回优势明显。

## 3. 内存（同一数据集：约 63 万键 × 64 B）

| 指标 | Tiering-KV | Redis 7 |
| --- | --- | --- |
| 引擎统计 `used_memory` | 107.5 MB | 100.5 MB |
| 容器 RSS（`docker stats`） | **1.345 GiB** | **100.6 MiB** |
| JVM 堆（`used_memory_jvm`） | 691 MB | — |

结论：**引擎自身的键值内存与 Redis 基本持平（+7%）**，但进程常驻内存
高出一个数量级——差额来自 JVM 堆预留、MetaSpace、Netty 直接内存与
Netty/分片队列。对以"省内存"为卖点的系统，这是必须正视的短板。

## 4. 超配（数据集 > 内存上限）实测

Tiering-KV 配置 `memory.limit=256MB`，写入 300 万随机键 × 64 B：

| 指标 | 结果 |
| --- | --- |
| SET 吞吐 | 55,576 rps（p50 0.471 ms） |
| 冷层产出 | `data/cold` 237 MB SSTable、`data/wal` 372 MB（**迁移确实发生**） |
| 引擎 `used_memory` | 73 MB / 256 MB |
| `DBSIZE` | 507,292（仅热层计数） |
| 容器 RSS | **2.46 GiB**（-Xmx2g） |

Redis 相同工作负载（300 万次写）：

| 指标 | 结果 |
| --- | --- |
| SET 吞吐 | 167,551 rps |
| `DBSIZE` | 1,896,323 |
| `used_memory` | 277.5 MB |
| 容器 RSS | 285 MiB |
| GET（300 万键空间） | 171,233 rps / p50 0.151 ms |
| Tiering-KV GET（同键空间） | 57,787 rps / p50 0.455 ms |

### 4.1 发现的缺陷：受约束堆下 OOM 而非降级

把 JVM 堆压到 `-Xmx512m`、引擎上限 256 MB，持续写入约 80 万键后：

```
Exception in thread "nioEventLoopGroup-3-21" java.lang.OutOfMemoryError: Java heap space
Exception: java.lang.OutOfMemoryError thrown from the UncaughtExceptionHandler in thread "nioEventLoopGroup-3-25"
Error: Connection reset by peer
```

服务端进程仍在（`docker ps` 显示 Up），但 Netty 事件循环线程已死，
后续连接全部被重置、`DBSIZE` 无响应。

**根因**：`MemoryManager` 的计量口径是"键 + 值字节数"，
而真实占用还包含 SkipList 节点、`byte[]` 对象头、WAL 缓冲与
16 个分片队列；当两者差距累积到堆上限，JVM 直接 OOM，
水位线触发的迁移还没来得及把数据搬到冷层。

**影响**：以内存上限作为容量承诺时不可靠；容器若设置 memory limit
会被 OOMKill。

**建议修复方向（TD-083）**：

1. 采集 `MemoryMXBean` 实际堆使用，与引擎计量加权（例如两者取大）；
2. 接近上限时主动拒绝写并返回 `ERR maxmemory`（与 Redis 语义一致），
   而不是让 JVM OOM；
3. 为 Netty 事件循环增加 `OutOfMemoryError` 兜底（关闭连接 + 记录 +
   触发主备切换/自愈），避免"进程活着但服务已死"的静默故障。

## 5. 结论

| 维度 | 结论 |
| --- | --- |
| 单请求延迟 | 约为 Redis 的 2×（0.079 ms vs 0.039 ms） |
| 吞吐 | 约为 Redis 的 1/3（并发）/ 1/4（流水线） |
| 键值内存 | 与 Redis 持平（+7%） |
| 进程内存 | 约为 Redis 的 13×（JVM 开销），是最大短板 |
| 冷热分层 | 迁移链路可用（SSTable 落盘），但内存上限未真正约束进程 |
| 稳定性 | 受约束堆下会 OOM 并导致连接重置（新发现缺陷） |

**答辩口径建议**：不要声称"比 Redis 快"或"比 Redis 省内存"，而应表述为
"在协议兼容的前提下给出了一个可验证的 Java 实现，并量化了与 Redis
的差距与改进方向"，把本次发现的 OOM 缺陷作为"真实测出来的工程问题"，
这比虚构性能更有说服力。

## 6. 待验证项

1. 冷层随机读命中率：需要确定性键名（而非 `redis-benchmark` 随机键）
   才能统计命中率与冷读延迟分布；
2. 开启 `fsync=NO` 与 Redis AOF off 对齐后的写路径差距；
3. 分片数（16）与 Netty worker 数对 c=200 吞吐回落的影响。
