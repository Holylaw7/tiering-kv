# ADR-0340: OBJECT / ACL / SCRIPT Command Families

## Status

Accepted

## Context

P2 剩余功能深度：Redis 管理面命令族。项目已有类型系统
（ValueType + TypedValueCodec）、静态默认用户与命令注册表；
无 Lua 运行时。

## Decision

- **OBJECT**（`OBJECT ENCODING/REFCOUNT/IDLETIME/FREQ`）：
  ENCODING 按 ValueType 映射（string 短值 embstr/长值 raw、
  hash→hashtable、list→quicklist、set→hashtable、zset→skiplist、
  stream→stream、json→json、timeseries→timeseries、
  vector→vector）；REFCOUNT=1、IDLETIME=0（无 LRU 跟踪，文档登记）、
  FREQ=-1（无 LFU 计数暴露）；缺失键返回 nil；
- **ACL**（只读子集）：WHOAMI=default、LIST（默认用户规则串）、
  CAT（静态类别清单）、GETUSER default；SETUSER 暂缓（文档登记）；
- **SCRIPT**：LOAD（SHA1 hex 注册表）、EXISTS（数组 0/1）、FLUSH；
  EVAL/EVALSHA 注册但返回显式
  `ERR scripting engine not available in this build`（无 Lua
  运行时，诚实登记，避免伪造执行）；
- 命令注册于默认注册表（127 → 132）。

## Alternatives

1. 引入 Lua 解释器（luaj）：依赖与 CVE 面增大，列为后续；
2. 不注册 EVAL：客户端收到 unknown command，语义不透明。

## Consequences

优点：管理面命令可发现/可脚本化（LOAD/EXISTS/FLUSH），类型编码
可观测。

缺点：ACL SETUSER 与 Lua 执行未交付（显式文档登记）。

风险：OBJECT ENCODING 与真实 Redis 内部编码存在差异——以项目
类型系统为准并在矩阵登记。

## Implementation

`command/ObjectCommand.java`、`command/AclCommand.java`、
`command/ScriptCommand.java` + 三组测试；注册 object/acl/script/
eval/evalsha（默认注册表 127 → 132，冻结计数同步）。
