# 网络架构（Network Architecture）

状态：基线（细化见 ADR-0003 与 Phase 1 设计）

## 1. 模型

```text
Client ── TCP ──▶ Netty Boss（accept）
                   └──▶ Worker（IO 事件）
                          ├── 解码（RESP）
                          ├── 投递 Command Engine（按 key 分片）
                          └── 背压（有界队列，满则 autoRead=false）
```

## 2. 组件

| 组件 | 职责 |
| --- | --- |
| tcp | 服务端引导、TCP 参数（Nagle 关闭、keepalive、backlog） |
| connection | 连接生命周期、状态、优雅关闭 |
| protocol | RESP 编解码（Phase 1） |

## 3. 设计约束

- IO 线程不做阻塞操作（磁盘、迁移、慢查询一律异步/出队）；
- 队列有界，背压信号回传客户端（-BUSY 或暂停读取）；
- 100k 连接目标：事件循环 + 有限线程，禁止 thread-per-connection。
