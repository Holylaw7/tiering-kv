# Phase 71 Review — OBJECT / ACL / SCRIPT Command Families

## 总体结论

P2 剩余管理面命令族完成（ADR-0340）：OBJECT 编码/计数、ACL 只读
子集、SCRIPT SHA1 注册表。默认注册表 127 → 132。全量回归
**14861 tests / 0 failures / 6 skipped**（本地），真实 Runner 门禁
6/6 全绿。

## 交付清单

1. OBJECT ENCODING/REFCOUNT/IDLETIME/FREQ：按项目类型系统映射
  （string 短值 embstr/长值 raw、hashtable/quicklist/skiplist/
  stream、json/timeseries/vector）；REFCOUNT=1、IDLETIME=0（无
  LRU 跟踪）、FREQ=-1（无 LFU 暴露）、缺失键 nil；
2. ACL WHOAMI/LIST/CAT/GETUSER：单默认用户（on nopass ~* &*
  +@all），未知类别/用户/子命令显式错误；SETUSER 暂缓（登记）；
3. SCRIPT LOAD（SHA1 注册表）/EXISTS/FLUSH；EVAL/EVALSHA 注册但
  显式返回 scripting engine not available（无 Lua，诚实登记）。

## 测试

- 新增 16 项：全部类型 ENCODING 映射、缺失 nil、REFCOUNT/IDLETIME/
  FREQ、ACL 四子命令 + 错误矩阵、SCRIPT SHA1/EXISTS/FLUSH/EVAL
  显式错误；
- 全量回归期间 HealthShutdownEdgeTest 与 ChaosClusterTest 命中
  已知时序 flaky（独立复跑 3/3 通过）。

## 已知限制

- OBJECT ENCODING 为项目类型口径（非 Redis 内部编码）；
- ACL 仅默认用户（SETUSER 暂缓）；EVAL 无 Lua 运行时。
