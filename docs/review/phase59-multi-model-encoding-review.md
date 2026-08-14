# Phase 59 Review — v4.0 M2 Multi-Model Encoding

## 总体结论

v4.0 M2（ADR-0320）完成：SQL/JSON/时序/向量作为一等类型化 KV 值，
additive 编码 + 命令入口 + 持久化/迁移/复制闭环 + RESP3 连接级接线。
全量回归 **14586 tests / 0 failures**（本地），真实 Runner 门禁 6/6
全绿（develop 双 run 均绿）。

## 交付清单

1. **类型扩展**：`ValueType` 增加 JSON / TIME_SERIES / VECTOR
   （类型字节 6/7/8，1–5 冻结）；`TypedValueCodec` switch 穷举同步；
2. **MultiModelCodec**：JSON（UTF-8）、时序（16B 二进制点）、向量
   （dim + float[]）编解码 + RESP3 映射（bulk / 嵌套数组 / double 数组）；
3. **命令族**：JSON.SET/GET、TS.ADD/GET/LEN、VECTOR.SET/GET，
   `TYPE` 支持 json/timeseries/vector；默认 115 命令注册表不变，
   扩展命令经 createDefaultWithVector 启用；
4. **M1 索引自动接线**：VECTOR.SET 成功即同步写入 VectorIndexStore，
   VECTOR.SEARCH 立即可查；覆盖刷新、非法写入不污染；
5. **持久化闭环**：WAL 崩溃恢复、SSTable 读写、冷迁移、复制投递
   四条路径对多模型值无损；
6. **TTL 语义**：类型化值按 key TTL 过期（到期前可解码、到期后 nil）；
7. **RESP3 接线**：VECTOR.GET / TS.GET 原生 RESP3 表达，JSON.GET
   保持 bulk；修复 RespEncoder `writeV3` 缺 RespArray 分支导致
   数组内 double 回退 RESP2 `:` 风格的缺陷。

## 测试与门禁

- 新增测试 21 项（命令 12 + 集成 5 + RESP3 wire 4，surefire 口径）；
- 全量回归 14586 / 0 failures / 6 skipped；
- 真实 Runner：build / test / transaction-e2e × main/develop 全绿；
- 基准（phase59-multi-model-encoding-report.md）：JSON 2.76M、
  时序 320K、向量 646K ops/s。

## 已知限制（如实记录）

- ~~JSON 语法合法性由上层解析层负责，存储层不校验~~ → 已解决：
  JsonValidator 结构级校验（括号/引号配对、尾随拒绝、顶层字面量），
  完整语义校验仍由解析层负责；
- ~~VECTOR 值经通用 `DEL` 删除时不会自动从 VectorIndexStore 移除~~ →
  已解决：VectorIndexSyncStorageEngine 装饰器在 put/delete 统一维护
  索引生命周期（含批量路径）；
- 类型字节空间有限，超 255 需版本化扩展（M3 前足够）；
- 多模型值的 SQL 谓词/join 语义（M2 仅标量 id 过滤）列入 M3。

## 后续

- v4.0 M3（多集群复制接线）：ADR-0321 启动；
- M2 增强项（DEL 索引同步、JSON 校验、RESP3 map 表达）按路线图排期。
