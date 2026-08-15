# ADR-0335: GEO Command Family

## Status

Accepted

## Context

P2 功能深度需要 GEO 命令族。Redis 内部以 ZSET + 52 位 geohash
score 存储地理位置，GEOPOS/GEOHASH 从 score 解码（文档口径，
坐标存在 geohash 精度误差）。项目已有 ZSetCodec（ADR-0276），
可完全复用 ZSET 语义实现 Redis 兼容。

## Decision

- GEOADD 将 (lon, lat, member) 编码为 52 位 geohash score 并以
  ValueType.ZSET + ZSetCodec 存储；TYPE 返回 zset，ZRANGE/ZSCORE
  等 ZSET 命令天然兼容；
- geohash 编码/解码采用 Redis geohash.c 同款：lat 范围
  [-85.05112878, 85.05112878]、lon 范围 [-180, 180]、26 bit 各、
  lon 偶数位 lat 奇数位交织；GEOHASH 输出 11 字符 base32；
- 距离采用 Redis haversine 常量 R=6372797.560856m；
  GEODIST/GEOSEARCH WITHDIST 以 %.4f 格式化；
- GEOSEARCH 支持 FROMMEMBER/FROMLONLAT × BYRADIUS/BYBOX +
  ASC/DESC + COUNT + WITHCOORD/WITHDIST/WITHHASH；
  GEORADIUS/GEORADIUSBYMEMBER 为兼容子集（STORE/STOREDIST 暂缓）；
- 非法坐标（lon 越界 / lat 越界）返回 Redis 风格错误；
- 检索为精确 haversine 过滤（O(N)），不依赖 geohash 网格剪枝
  （当前规模正确性优先，性能列入 P3）。

## Alternatives

1. 独立 GEO 类型：破坏 TYPE=zset 与 ZSET 命令兼容；
2. 引入空间索引（R-tree）：复杂度高，当前规模无必要。

## Consequences

优点：Redis 语义对齐（含文档基准 geohash 字符串与距离）、复用
ZSET 持久化/复制/迁移链路。

缺点：GEOPOS 返回近似坐标（Redis 同款口径）；大集合 GEOSEARCH
为 O(N)。

风险：geohash 位序/范围参数与 Redis 不一致会导致文档基准失败——
以 Redis 官方示例（Palermo/Catania）为回归锚点。

## Implementation

`storage/types/GeoHash.java`（encode52/decode/hashString/haversine）、
`command/GeoCommand.java` + `GeoCommandFamilyTest`；注册 geoadd/
geopos/geodist/geohash/geosearch/georadius/georadiusbymember。
