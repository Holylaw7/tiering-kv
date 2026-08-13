# SQL Production Hardening

## 错误语义

`SqlProductionSupport.errorOf` 归一化：

| 错误码 | 匹配 |
| --- | --- |
| SYNTAX_ERROR | syntax / parse |
| TYPE_ERROR | type / cannot cast |
| UNKNOWN_COLUMN | column / not found |
| INTERNAL_ERROR | 其他 |

## EXPLAIN

`SqlProductionSupport.explain(plan)`：节点编号 + 类型（SCAN/FILTER/
JOIN/AGGREGATE）+ 详情 + summary（scans/joins/aggregates/pushdown）。
