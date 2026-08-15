# Phase 72 Review — RESP3 Full Types（P2 收官）

## 总体结论

RESP3 完整类型命令级接线完成（ADR-0341）：null `_`、HELLO/CONFIG
map、集合族 set。P2 全部完成。全量回归 **14868 tests / 0 failures /
6 skipped**（本地），真实 Runner 门禁 6/6 全绿。

## 交付清单

1. RespEncoder.writeV3：RespNull → `_\r\n`（RESP2 保持 `$-1`/
  `*-1`）；
2. HELLO 3 / CONFIG GET → RespMap（RESP2 平铺数组回退）；
3. SMEMBERS/SINTER/SUNION/SDIFF/SPOP count → RespSet（RESP2 数组
  回退）；SRANDMEMBER 保持数组；
4. 相关修复：SRANDMEMBER 正值计数去重抽取（抽后移除）、负值保留
  重复元素（Redis 语义）。

## 测试

- 新增 7 项字节级 wire 测试（RESP3 原生 + RESP2 回退双口径）；
- 既有 Resp3WireTest HELLO 断言升级为 Map 语义；
- 全量回归期间 MetadataPersistenceTest 命中已知时序 flaky
  （独立复跑 3/3 通过）。

## 已知限制

- PubSub RESP3 push 消息尚未命令级接线（编码器已支持，登记）；
- 版本感知为逐命令模式（非全自动 schema 转换）。

## P2 完结声明

P2 功能深度全部交付：BIT/GEO、JSON 路径、时序、向量多集合、
跨集群 2PC、OBJECT/ACL/SCRIPT、RESP3 完整类型。
