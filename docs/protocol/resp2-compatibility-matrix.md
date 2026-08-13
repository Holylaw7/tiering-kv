# RESP2 Compatibility Matrix

## 基准

以 Redis 7.x 文档语义为参照；差异项在本文件如实登记。

## 回复形态

| 命令 | 回复 | 缺失/边界 |
| --- | --- | --- |
| GET | bulk / nil | 缺失 = nil |
| STRLEN | integer | 缺失 = 0 |
| INCR/DECR/INCRBY/DECRBY | integer | 非整数 = ERR |
| APPEND | integer（新长度） | 缺失按空串 |
| GETSET | bulk / nil | 清除 TTL |
| SETNX | integer 1/0 | — |
| TTL/PTTL | integer | -2 缺失 / -1 无 TTL |
| EXPIRE/PERSIST | integer 1/0 | — |
| DEL | integer | 重复键只计一次 |
| EXISTS | integer | 逐参数计数 |
| MGET | array | 缺失元素 = nil |
| DBSIZE | integer | — |
| TYPE | simple | string / none |
| SCAN | [cursor, array] | 无效游标 = 空 |

## 错误文本

- `ERR wrong number of arguments for '<cmd>' command`
- `ERR unknown command '<cmd>'`
- `ERR value is not an integer or out of range`
- `ERR Unsupported CONFIG parameter: <param>`
- `ERR offset is out of range`
- `CROSSSLOT Keys in request don't hash to the same slot`

## 已知差异

- CLIENT GETNAME 恒返回 nil（无会话态）；
- TTL 取整为向下取整（ms/1000）。
