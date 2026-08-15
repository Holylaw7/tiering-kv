# ADR-0336: JSON Path Command Family

## Status

Accepted

## Context

P2 功能深度需要 RedisJSON 风格 JSON 路径操作。现有 JSON 能力仅有
`JsonValidator`（括号/引号结构校验，ADR-0320）与无路径的
`json.set/json.get`（MultiModelCommand）。完整路径操作需要可靠
的 JSON 解析/序列化与路径求值/变更。

## Decision

- 引入 `com.fasterxml.jackson.core:jackson-databind`（2.18.2）作为
  JSON 树解析/序列化基础，禁止手写完整 JSON 解析器（转义/数字/
  Unicode 边界过多）；
- 自研 Redis JSON 路径子集（`command/JsonPath`）：
  - 读路径：`$`、`.field`、`['field']`/`["field"]`、`[n]`（含负索引）、
    `.*`/`[*]` 通配、`..field`/`..*` 递归下降；
  - 变更路径（SET/DEL/NUMINCRBY/ARRAPPEND）：`$` 与简单字段/索引链，
    通配/递归用于 SET/DEL 暂不支持（已知差异，文档登记）；
- 命令族：JSON.SET（路径默认 `$`，NX/XX，缺失中间对象按字段创建）、
  JSON.GET（多路径返回 {path: result}；`$` 路径返回匹配数组）、
  JSON.DEL（返回删除数）、JSON.TYPE、JSON.ARRAPPEND、JSON.ARRLEN、
  JSON.OBJKEYS、JSON.OBJLEN、JSON.STRLEN、JSON.NUMINCRBY；
  JSON.SET/DEL/NUMINCRBY/ARRAPPEND 经 TypeSupport.update 原子变更
  并保留 TTL；
- 替换既有无路径 `json.set/json.get`（保持无路径行为不变，兼容
  MultiModelCommandTest 直连用例）。

## Alternatives

1. 引入 jayway json-path：语义与 RedisJSON 差异大、依赖链重；
2. 自写完整 JSON 解析器：转义/数字边界风险高。

## Consequences

优点：解析正确性由 Jackson 保证，路径语义与 Redis 文档对齐，依赖
单一（jackson-databind）。

缺点：路径变更子集（SET/DEL 不支持通配/递归）；路径读取结果经
Jackson 重序列化（数字格式可能规范化）。

风险：依赖版本 CVE——固定 2.18.2（2024-47535 修复版），release
Trivy 门禁复验。

## Implementation

`command/JsonPath.java`（tokenizer/evaluator）、
`command/JsonCommand.java`（10 命令）、`JsonCommandFamilyTest`；
扩展注册表 json.set/json.get 替换 + 8 新命令（默认注册表 127 不变）。
