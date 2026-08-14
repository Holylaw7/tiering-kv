# Phase 59 — v4.0 M2 Multi-Model Encoding

## Context

v4.0 M2（RFC-0001 / ADR-0320）：SQL/JSON/时序/向量作为一等值类型，
additive 编码 + RESP3 接线。基线：v4.0 M1 完成（Phase 58 归档）。

## Goal

1. ValueType 扩展 JSON / TIME_SERIES / VECTOR（类型字节 6/7/8）
2. TypedValueCodec 同步支持（1–5 冻结不变）
3. MultiModelCodec：JSON / 时序 / 向量 payload 编解码
4. RESP3 映射（JSON bulk / 时序数组 / 向量数组）
5. 测试 + 基准 + 全量回归 0 failures

## 交付

| 模块 | 文件 |
| --- | --- |
| 类型扩展 | storage/types/ValueType.java、TypedValueCodec.java |
| 编码器 | storage/types/MultiModelCodec.java |
| RESP3 映射 | MultiModelCodec 内映射方法（不新增协议层） |
| 测试 | storage/types/MultiModelCodecTest.java |
| 基准 | docs/benchmark/phase59-multi-model-encoding-report.md |

## Test Plan

- JSON：encode/decode roundtrip、UTF-8 多字节、非法维度拒绝
- TIME_SERIES：roundtrip、空序列、乱序保持原序
- VECTOR：roundtrip、dim 校验、空向量拒绝
- 兼容：类型字节 1–5 既有测试不破坏；未知类型字节拒绝
- RESP3：JSON→bulk、时序→嵌套数组、向量→double 数组
- 全量回归 0 failures；新增测试 ≥40

## 验收

- ADR-0320 已批准（本文档引用）
- 本地全量回归 0 failures；真实 Runner 门禁 6/6
- 基准报告输出编码吞吐
- Conventional Commit 拆分提交
