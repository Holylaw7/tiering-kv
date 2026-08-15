# Phase 67 — P2 功能深度：JSON 路径命令族

## Context

Optimization Roadmap P2 第二交付：RedisJSON 风格 JSON 路径操作。
基线：BIT/GEO 完成（Phase 66），注册表 127 命令，JSON 仅有结构校验
与无路径读写。

## Goal

1. ADR-0336 已批准（本阶段）
2. 引入 jackson-databind 2.18.2（JSON 树 + 序列化）
3. JsonPath 子集：`$`/`.field`/`['field']`/`[n]`/`.*`/`[*]`/`..`
4. 命令：JSON.SET/GET/DEL/TYPE/ARRAPPEND/ARRLEN/OBJKEYS/OBJLEN/
   STRLEN/NUMINCRBY；SET/DEL/NUMINCRBY/ARRAPPEND 原子 + 保留 TTL
5. 全量回归 0 failures + 真实 Runner 门禁

## 交付

| 模块 | 文件 |
| --- | --- |
| 依赖 | pom.xml（jackson-databind 2.18.2） |
| 路径 | command/JsonPath.java |
| 命令 | command/JsonCommand.java（10 命令） |
| 测试 | command/JsonCommandFamilyTest |
| 注册 | CommandRegistry 扩展注册表 +8（json.set/get 替换为路径版；默认注册表 127 不变） |
| 文档 | ADR-0336、command-family-design、RESP2 矩阵、CHANGELOG |

## Test Plan

- SET：根替换/嵌套创建/已有字段覆盖/NX/XX/非法 JSON/非 JSON 键
  WRONGTYPE/TTL 保留
- GET：无路径原样返回、legacy 单值、`$` 路径数组、多路径对象、
  未命中（legacy nil / JSONPath 空数组）
- DEL：根/嵌套/缺失计数
- TYPE/ARRLEN/OBJLEN/STRLEN/OBJKEYS：legacy 单值与 JSONPath 数组
- ARRAPPEND：追加多值/非数组错误/新长度
- NUMINCRBY：整数/浮点/非数字错误/未命中
- 通配/递归读：`.*`、`[*]`、`..field`
- 全量回归 0 failures；新增测试 ≥35

## 验收

- ADR-0336 已批准；Conventional Commit 拆分
- RedisJSON 文档示例路径语义通过（如 `$.store.book[0].title`）
- 全量回归 0 failures；真实 Runner 门禁 6/6
