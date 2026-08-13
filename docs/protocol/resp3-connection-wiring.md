# RESP3 Connection Wiring

## 架构

```text
ConnectionContext (version + subscriber + txn queue)
    ↑ ThreadLocal（事件循环 / 异步 worker attach）
CommandEngine / Commands 读取
    ↓
RespEncoder.write(buf, value, version)
    ↓
ResponseBatcher（每连接版本）
```

## 行为

- HELLO 3 → 当前连接切换 RESP3；HELLO 2 回退；
- HGETALL：RESP3 Map / RESP2 平铺数组；
- SMEMBERS：RESP3 Set / RESP2 数组；
- Push 消息：RESP3 `>` / RESP2 数组；
- 连接关闭：退订 + 清队列 + 重置版本。
