# 网络架构（Network Architecture）

状态：✅ Phase 1 已实现（ADR-0003 / ADR-0006）

## 1. 模型

```text
Client ── TCP ──▶ Netty Boss（accept）
                   └──▶ Worker（IO 事件）
                          ├── RespDecoder（RESP2 增量解析）
                          ├── CommandHandler（解析请求 → 执行 → 写回）
                          └── RespEncoder（RESP2 编码）
```

Phase 1 执行模型：命令在连接事件循环内同步执行（单连接串行）；
Phase 7 升级为有界线程池 + key 分片执行器。

## 2. 组件

| 组件 | 职责 |
| --- | --- |
| tcp | 服务端引导、TCP 参数（Nagle 关闭、keepalive、backlog） |
| connection | 连接生命周期、状态、优雅关闭 |
| protocol | RESP 编解码（Phase 1） |

## 3. 设计约束

- IO 线程不做阻塞操作（磁盘、迁移、慢查询一律异步/出队）；
- Phase 7 起队列有界，背压信号回传客户端（-BUSY 或暂停读取）；
- 100k 连接目标：事件循环 + 有限线程，禁止 thread-per-connection。

## 4. 实现状态（Phase 1）

- `io.tieringkv.network.tcp.TieringKvServer`：Netty NIO 引导与生命周期；
- `io.tieringkv.network.connection.ConnectionInitializer`：管道组装；
- `io.tieringkv.network.connection.CommandHandler`：请求解析、执行、错误关闭；
- 连接级串行执行保证单连接命令有序；协议错误写入后关闭连接。
- 管道顺序注意：Netty 出站事件自调用点向 head 传播，RESP Encoder 必须位于
  CommandHandler 之前（当前顺序：encoder → decoder → handler）。
