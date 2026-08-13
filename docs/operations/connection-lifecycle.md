# Connection Lifecycle

## 清理闭环

连接关闭（channelInactive）：

1. broker.unsubscribeAll(连接订阅者)；
2. 清空事务队列并退出 MULTI；
3. 重置协议版本 RESP2；
4. 清空订阅队列与丢弃计数。

验证：订阅计数归零、队列清空、版本重置（SessionLifecycle 矩阵）。
