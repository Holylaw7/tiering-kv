# Final Performance Whitepaper

## 口径

全部为 LOCAL 进程内口径（surefire 基准输出 PHASE*-BENCH-*）；
跨机/跨地域项封板待真实 Runner，禁止外推。

## 核心路径摘要

| 路径 | 结果 | 口径 |
| --- | --- | --- |
| MemTable GET/SET | 4M+ ops/s（Phase 9） | LOCAL |
| 服务端 pipeline64 | 465K ops/s（Phase 10） | LOCAL |
| WAL append | 1.4–1.6M ops/s | LOCAL |
| 事务 SET | 144–175K ops/s | LOCAL |
| 数据结构（HSET/SADD/ZADD） | 16–132K ops/s | LOCAL |
| MULTI/EXEC | 62–139K ops/s | LOCAL |
| XADD | 1.5–4.4K ops/s | LOCAL（整值重写） |

## 容量模型

- 内存：MemTable 配额 + 水位；冷数据迁移磁盘；
- 磁盘：WAL + SSTable + 归档；写放大登记；
- CPU：KeyShardExecutor（同键 FIFO）+ 后台 worker；
- 网络：RPC 帧 + 响应批处理。

## 结论

瓶颈位于网络/RESP/调度层（Phase 9 结论持续成立）；存储路径已具备
生产基线，真实跨机数字待 Runner 补充。
