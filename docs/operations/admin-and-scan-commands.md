# Admin & Scan Commands

## 命令

| 命令 | 行为 |
| --- | --- |
| DBSIZE | 当前键数量 |
| FLUSHDB / FLUSHALL | 清空（单库等价） |
| TYPE | string / none |
| SCAN cursor [COUNT n] [MATCH p] | 快照游标遍历 |
| CONFIG GET/SET | 白名单配置 |
| CLIENT SETNAME/GETNAME | OK / nil（无会话态） |
| COMMAND COUNT/INFO | 注册表元数据 |

## SCAN 语义

- cursor 0 构建有序键快照并分配游标 id；
- 后续调用按 offset 切片，游标归零时释放快照；
- MATCH 支持 `*` 与 `?`；
- COUNT 默认 10，<=0 回退默认值。

## CONFIG 白名单

maxmemory / appendfsync / timeout / save / maxclients；
未知参数返回 `ERR Unsupported CONFIG parameter`。
