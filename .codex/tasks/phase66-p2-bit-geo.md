# Phase 66 — P2 功能深度：BIT / GEO 命令族

## Context

Optimization Roadmap P2 功能深度第一交付：Redis BIT 与 GEO 命令族。
基线：P1（技术债清偿）全部归档，命令注册表 115 条。

## Goal

1. ADR-0334（BIT）/ ADR-0335（GEO）已批准（本阶段）
2. BIT：SETBIT / GETBIT / BITCOUNT（BYTE/BIT 范围）/ BITPOS /
   BITOP（AND/OR/XOR/NOT）
3. GEO：GEOADD（NX/XX/CH）/ GEOPOS / GEODIST（m/km/mi/ft）/
   GEOHASH / GEOSEARCH（FROMMEMBER/FROMLONLAT + BYRADIUS/BYBOX +
   ASC/DESC + COUNT + WITHCOORD/WITHDIST/WITHHASH）/
   GEORADIUS / GEORADIUSBYMEMBER
4. GEO 按 Redis 语义以 ZSET + 52 位 geohash score 存储（TYPE=zset、
   ZRANGE/ZSCORE 兼容）；GEOPOS 从 geohash 解码（Redis 文档口径）
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| BIT | command/BitCommand.java + BitCommandFamilyTest |
| GEO 编码 | storage/types/GeoHash.java（geohash52 编解码 + haversine） |
| GEO | command/GeoCommand.java + GeoCommandFamilyTest |
| 注册 | CommandRegistry + 7 处 115→129 冻结计数 |
| 文档 | ADR-0334/0335、command-family-design、CHANGELOG/ROADMAP |

## Test Plan

- BIT：置位/读取/扩展、BITCOUNT 全量与 BYTE/BIT 范围、BITPOS 各类
  边界（缺失键/全 0/全 1/范围未命中）、BITOP 四运算 + 不等长零填充、
  WRONGTYPE、负数 offset 拒绝
- GEO：GEOADD 计数与 NX/XX/CH、GEOPOS 精度容差（±0.001）、GEODIST
  Redis 文档基准（Palermo–Catania ≈166274.1516m）、GEOHASH Redis 文档
  基准（sqc8b49rny0/sqdtr74hyu0）、GEOSEARCH 半径/矩形/排序/COUNT/
  WITH 选项、GEORADIUS/BYMEMBER、非法坐标拒绝、TYPE=zset、
  ZRANGE/ZSCORE 兼容
- 全量回归 0 failures；新增测试 ≥30

## 验收

- 两 ADR 已批准；Conventional Commit 拆分（feat(command): bit / feat(command): geo / test(ci): counts / docs）
- Redis 文档 GEO 基准用例通过（geohash 字符串与距离）
- 全量回归 0 failures；真实 Runner 门禁 6/6
