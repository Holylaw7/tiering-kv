# Pub/Sub Network Delivery

## 连接投递

- SUBSCRIBE/PSUBSCRIBE 注册当前连接订阅者；
- 有界队列（默认 1024），溢出丢最旧并计数；
- 事件循环在响应批次后 drain 并写出 Push/数组。

## 集群广播

- `RpcPubSubForwarder`：peer 注册 + RPC PUBSUB 帧；
- 环回抑制：不转发回来源节点；
- best-effort：失败登记不阻塞发布；
- 接收端 `RpcPubSubBridge`：解码 → 本地 broker.publish → ACK。

## 限制

- 至少一次语义需消费方幂等；不落盘。
