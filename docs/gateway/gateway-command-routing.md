# Gateway Command Routing

## 路由规则

| 命令类型 | 行为 |
| --- | --- |
| 单键（INCR/APPEND/TTL/EXPIRE/TYPE 等） | 按 slot 路由，非本地 MOVED |
| 多键（MGET/MSET/MSETNX/DEL/EXISTS） | 全部同槽，跨槽 CROSSSLOT |
| 节点本地（SCAN/DBSIZE/FLUSHDB/CONFIG/CLIENT/COMMAND） | 本地执行 |

## 实现

网关复用 `CommandEngine + CommandRegistry + 本地存储`，避免复制命令
语义；事务网关路径（AutoTransactionExecutor）保持既有 GET/SET/DEL/
MGET/MSET 行为，新命令走命令引擎。
