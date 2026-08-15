# Phase 71 — P2 剩余项：OBJECT / ACL / SCRIPT 命令族

## Context

P2 功能深度剩余管理面命令族。基线：P2 主体归档（Phase 70），
注册表 127 命令，无 Lua 运行时。

## Goal

1. ADR-0340 已批准（本阶段）
2. OBJECT ENCODING/REFCOUNT/IDLETIME/FREQ（类型映射 + 缺失 nil）
3. ACL WHOAMI/LIST/CAT/GETUSER（只读子集，SETUSER 暂缓登记）
4. SCRIPT LOAD/EXISTS/FLUSH（SHA1 注册表）；EVAL/EVALSHA 显式
   "not available"（无 Lua，诚实登记）
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| OBJECT | command/ObjectCommand.java + ObjectCommandFamilyTest |
| ACL | command/AclCommand.java + AclCommandFamilyTest |
| SCRIPT | command/ScriptCommand.java + ScriptCommandFamilyTest |
| 注册 | CommandRegistry（127 → 132）+ 7 处冻结计数 |
| 文档 | ADR-0340、command-family-design、RESP2 矩阵、CHANGELOG |

## Test Plan

- OBJECT：各类型 ENCODING 映射（embstr/raw/hashtable/quicklist/
  skiplist/stream/json/timeseries/vector）、缺失 nil、REFCOUNT/
  IDLETIME/FREQ 边界、arity
- ACL：WHOAMI/LIST/CAT（含未知类别错误）/GETUSER default/未知子命令
- SCRIPT：LOAD 返回 40 位 SHA1、EXISTS 0/1、FLUSH、EVAL/EVALSHA
  显式错误
- 全量回归 0 failures；新增测试 ≥25

## 验收

- ADR-0340 已批准；Conventional Commit 拆分
- 冻结计数 132 更新（7 处）
- 全量回归 0 failures；真实 Runner 门禁 6/6
