# 摘要

内存键值数据库以低延迟支撑了大量在线业务，但其容量受物理内存约束：当数据集增长到数十 GB 以上时，内存成本成为瓶颈，而其中真正被频繁访问的往往只是一小部分热数据。针对这一问题，本文设计并实现了一个 Redis 协议兼容、面向冷热数据分层的键值存储引擎，以及与之配套的独立 Web 运维控制台。

系统分为数据平面与控制平面两个进程。数据平面自行实现 LSM 主链路：分段预写日志、分片有序内存表、带块索引与布隆过滤器的不可变磁盘表，以及按表数阈值触发的合并；在此之上以水位线驱动落盘、异步迁移与背压，使内存占用成为可配置的硬约束，冷数据在读取时按需回源并写回热层。控制平面通过标准 RESP 协议纳管内核，提供认证授权、指标看板、键空间浏览、在线终端、告警、备份恢复与审计，其观测数据全部来自内核计数，并对"请求失败、内核未上报、真实为零"三种状态分别呈现。

验证方面，本文建立了单元与集成测试、端到端业务闭环、故障注入、性能基准与覆盖率五个层次的证据链。内核全量回归执行 15014 次、零失败；控制平面 93 例测试、行覆盖率 62.57%；七条业务闭环共 91 条断言、四类故障注入共 22 条断言全部通过。验证过程中发现并修复了预写日志空闲期不落盘、同实例并发备份双双成功、失败操作审计被事务回滚等真实缺陷，其中前者的崩溃丢失窗口由"任意量"收敛为实测 420.8 毫秒。

实验表明：在承担预写日志持久化成本的前提下，系统吞吐达到同机 Redis 的 0.36 至 0.47 倍；分层调度在十万至百万条目规模下迁移成功率 100%；观测开销在 8 张表规模下对业务延迟无可测影响。本文同时明确标注了尚未验证的边界：分布式能力仅在单机多进程环境验证，时间戳机制不等于完整的多版本并发控制，进程崩溃恢复不等于掉电安全。

关键词：键值存储；冷热分层；LSM-Tree；可观测性；工程验证

# Abstract

In-memory key-value databases support a large number of online services with low latency, but their capacity is bounded by physical memory. When the data set grows beyond tens of gigabytes, memory cost becomes the bottleneck, while only a small fraction of the data is frequently accessed. This thesis designs and implements a Redis-protocol-compatible key-value storage engine with hot/cold data tiering, together with an independent web-based operations console.

The system is split into a data plane and a control plane. The data plane implements the LSM pipeline from scratch: a segmented write-ahead log, a sharded ordered memtable, immutable SSTables with block index and Bloom filter, and compaction triggered by table count. On top of it, watermark-driven flush, asynchronous migration and back pressure turn memory usage into a configurable hard constraint, and cold reads are served on demand and copied back to the hot tier. The control plane manages engine instances over the standard RESP protocol and provides authentication, metrics, keyspace browsing, an online terminal, alerting, backup and restore, and auditing. All observation data come from engine counters, and three states — request failure, metric not reported, and a genuine zero — are presented separately.

Verification follows a five-layer evidence chain: unit and integration tests, end-to-end business loops, fault injection, performance benchmarks, and coverage. The full kernel regression executed 15014 tests with zero failures; the control plane has 93 tests with 62.57% line coverage; seven business loops contribute 91 assertions and four fault-injection scenarios contribute 22 assertions, all passing. Real defects were found and fixed, including a write-ahead log that never flushed while idle, concurrent duplicate backups on the same instance, and audit records rolled back together with failed operations. The crash loss window of the first defect was reduced from unbounded to a measured 420.8 milliseconds.

Experiments show that, while carrying the cost of write-ahead logging, throughput reaches 0.36 to 0.47 times that of Redis on the same host; tiering migration achieves a 100% success rate from one hundred thousand to one million entries; and observation overhead has no measurable impact on latency at the eight-table scale. The thesis also states explicit boundaries: distributed capabilities are verified only in a single-machine multi-process setting, the timestamp mechanism is not full multi-version concurrency control, and process-crash recovery is not power-failure safety.

Key words: key-value store; hot/cold tiering; LSM-Tree; observability; engineering verification
