# 协议详细设计（Protocol Design）

状态：✅ 已实现（Phase 1，ADR-0006）

## 1. 协议版本

RESP2（详见 [ADR-0006](../adr/ADR-0006-resp-protocol.md)）。请求 = bulk string
数组；响应类型：

| 类型 | 格式 | 用途 |
| --- | --- | --- |
| 简单字符串 | `+OK\r\n` | PING 无参、SET 成功 |
| 错误 | `-ERR msg\r\n` | 未知命令 / 参数错误 / 协议错误 |
| 整数 | `:1\r\n` | DEL / EXISTS 计数 |
| Bulk 字符串 | `$5\r\nhello\r\n` | GET / ECHO / PING 带参 |
| Null Bulk | `$-1\r\n` | GET 未命中 |
| 数组 | `*2\r\n...` | 请求与复合响应 |

## 2. 解码器（RespDecoder）

- 增量解析：数据不足时回退读指针，等待后续字节（ByteToMessageDecoder）；
- 单缓冲区支持 pipeline 多条命令（循环解析直至输入不足）；
- 上限常量集中定义：行 64KiB、bulk 512MiB、数组元素 1Mi；
- 协议错误抛 `RespProtocolException` → `-ERR Protocol error: ...` 并关闭连接；
- inline 命令按空白分词（无引号支持，已知限制）。

## 3. 编码器（RespEncoder）

- `MessageToByteEncoder<RespValue>`，纯函数式输出；
- 错误/简单字符串中的 CR/LF 替换为空格，防止响应注入。

## 4. 命令层

- `RespRequestParser`：RespArray → RespCommand（命令名小写化，参数为 byte[]）；
- `CommandRegistry`：启动构建、运行期只读；未知命令 → `ERR unknown command 'x'`；
- 参数数量错误 → `ERR wrong number of arguments for 'x' command`；
- 命令集（Phase 1）：PING / ECHO / SET / GET / DEL / EXISTS；
- `KVStore` 接口 + `InMemoryKVStore`（Phase 2 由 MemTable 替换）。

## 5. 执行模型

- Phase 1：连接事件循环内同步执行（单连接有序、无跨连接竞态）；
- Phase 7：有界线程池 + key 分片执行器（ADR-0003）；
- 背压：事件循环直写模式由 Netty writability 机制天然背压。

## 6. 待定项

- RESP3 升级（新 ADR）；
- inline 引号解析；
- SET 的 EX/PX/TTL 参数（Phase 2）；
- 超大请求配额策略（Phase 10）。
