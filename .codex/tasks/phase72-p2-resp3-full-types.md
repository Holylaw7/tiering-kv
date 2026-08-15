# Phase 72 — P2 剩余项：RESP3 完整类型

## Context

P2 最后一项。基线：RESP3 编码器支持 Map/Set/Push/Double，连接态
版本协商已接线；HGETALL/SMEMBERS 已版本感知。

## Goal

1. ADR-0341 已批准（本阶段）
2. RESP3 null 编码 `_\r\n`（RESP2 不变）
3. HELLO 3 / CONFIG GET → RespMap；集合族（SMEMBERS/SINTER/SUNION/
   SDIFF/SPOP count）→ RespSet
4. 字节级 wire 测试（RESP3 原生 + RESP2 回退）+ 全量回归
5. 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 编码 | protocol/RespEncoder.java（null） |
| 命令 | HelloCommand / ConfigCommand / SetFamilyCommand（版本感知） |
| 测试 | protocol/Resp3FullTypeWireTest |
| 文档 | ADR-0341、resp2-compatibility-matrix、CHANGELOG |

## Test Plan

- RESP3 null：`_\r\n`；RESP2 `$-1`/`*-1`
- HELLO 3 → `%4` map；HELLO 2 → 数组
- CONFIG GET → `%N` map / 数组回退
- SMEMBERS/SINTER/SUNION/SDIFF/SPOP count → `~N` set / 数组回退
- HGETALL RESP3 map 回归；SRANDMEMBER 保持数组
- 全量回归 0 failures；新增测试 ≥12

## 验收

- ADR-0341 已批准；Conventional Commit 拆分
- 字节级 wire 断言通过（RESP3 与 RESP2 双口径）
- 全量回归 0 failures；真实 Runner 门禁 6/6
