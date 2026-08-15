# ADR-0337: Time Series Command Family

## Status

Accepted

## Context

P2 功能深度需要时序查询/聚合/下采样。现有 TIME_SERIES 仅为
`ts.add/ts.get/ts.len` 基础读写（ADR-0320，MultiModelCodec.TimePoint
列表）。需要范围查询、桶聚合、增量写入与多键范围查询。

## Decision

- 复用 TIME_SERIES 编码（timestamp + double 列表，冻结字节不变），
  新增 `TimeSeriesCommand`：
  - `TS.RANGE key from to [AGGREGATION agg bucket] [COUNT n]`：
    返回 [ts, value] 数组；AGGREGATION 按 `floorDiv(ts, bucket)*bucket`
    对齐桶并聚合（AVG/SUM/MIN/MAX/COUNT/FIRST/LAST），COUNT 限制
    输出条数；查询前按时间戳稳定排序；
  - `TS.INCRBY key value [TIMESTAMP ts]`：显式 TIMESTAMP 时同刻
    累加/新刻追加；省略时以当前毫秒（与最后样本同刻则累加）；
    经 TypeSupport.update 原子执行并保留 TTL，返回样本时间戳；
  - `TS.MRANGE from to [AGGREGATION agg bucket] [COUNT n]`：遍历
    存储中全部 TIME_SERIES 键（按键名字典序），返回
    [[key, samples]...]；非 TIME_SERIES 键跳过（无标签/无 FILTER，
    已知差异）；
  - `TS.REDUCE key [AGGREGATION agg]`：全序列聚合（项目扩展，
    返回 [首点时间戳, 聚合值]；空序列返回 nil array）；
- 聚合子集：AVG/SUM/MIN/MAX/COUNT/FIRST/LAST（RANGE/STD/VAR/TWA
  暂缓，文档登记）；
- 命令注册于扩展注册表（createDefaultWithVector），默认注册表不变。

## Alternatives

1. 引入 RedisTimeSeries 依赖：服务形态不一致、重；
2. 只做 TS.RANGE：增量与多键能力缺失。

## Consequences

优点：复用冻结编码，查询/聚合语义与 RedisTimeSeries 文档对齐
（桶对齐、聚合名），原子写保留 TTL。

缺点：MRANGE 无标签过滤；REDUCE 为非标准扩展；桶聚合内存 O(N)。

风险：桶对齐负时间戳用 floorDiv 处理（已测试）。

## Implementation

`command/TimeSeriesCommand.java` + `TimeSeriesCommandFamilyTest`；
扩展注册表新增 ts.range/ts.mrange/ts.incrby/ts.reduce。
