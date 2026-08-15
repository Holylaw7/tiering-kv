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
| SETBIT | integer（旧位） | offset 越界/非 0/1 = ERR |
| GETBIT | integer 1/0 | 缺失键 = 0 |
| BITCOUNT | integer | 支持 BYTE/BIT 范围、负索引 |
| BITPOS | integer | 缺失键 bit=0 → 0 / bit=1 → -1 |
| BITOP | integer（结果长度） | NOT 仅单源；缺失源按零串 |
| GEOADD | integer | NX/XX/CH；非法坐标 = ERR |
| GEOPOS | array（[lon,lat]） | 缺失成员 = nil array |
| GEODIST | bulk | 缺失成员 = nil；m/km/mi/ft |
| GEOHASH | array（11 字符） | 缺失成员 = nil |
| GEOSEARCH | array | FROMMEMBER/FROMLONLAT × BYRADIUS/BYBOX |
| GEORADIUS(BYMEMBER) | array | STORE/STOREDIST 暂缓 |
| JSON.SET | simple OK / nil | NX/XX；非法 JSON = ERR；扩展注册表 |
| JSON.GET | bulk | 多路径 = 对象；JSONPath = 匹配数组文本 |
| JSON.DEL | integer | 根删除整键；通配/递归 DEL 暂缓 |
| JSON.TYPE/ARRLEN/OBJLEN/STRLEN | bulk/integer/array | legacy 单值；JSONPath 数组 |
| JSON.OBJKEYS | array | legacy 键数组；JSONPath 数组套数组 |
| JSON.ARRAPPEND/NUMINCRBY | integer/bulk | 路径未命中 = ERR |

## 错误文本

- `ERR wrong number of arguments for '<cmd>' command`
- `ERR unknown command '<cmd>'`
- `ERR value is not an integer or out of range`
- `ERR Unsupported CONFIG parameter: <param>`
- `ERR offset is out of range`
- `CROSSSLOT Keys in request don't hash to the same slot`

## 已知差异

- CLIENT GETNAME 恒返回 nil（无会话态）；
- TTL 取整为向下取整（ms/1000）；
- GEORADIUS/GEOSEARCH 的 STORE/STOREDIST/GEOSEARCHSTORE 未实现；
- GEO 检索为 O(N) 精确过滤（未做 geohash 网格剪枝）；
- JSON SET/DEL 不支持通配/递归路径；JSON 数字经 Jackson 序列化可能
  规范化格式；JSON 命令仅在扩展注册表（createDefaultWithVector）。
