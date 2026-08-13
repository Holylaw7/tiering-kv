# Pub/Sub Guide

## 能力

- SUBSCRIBE/UNSUBSCRIBE/PSUBSCRIBE/PUNSUBSCRIBE/PUBLISH；
- 本地 broker 至少一次投递（直接 + 模式订阅，* / ? 通配）；
- `PubSubForwarder` SPI：集群广播预留（网络实现 Phase 53+）。

## 命令

```bash
redis-cli SUBSCRIBE news
redis-cli PUBLISH news hello
redis-cli PSUBSCRIBE user:*
```

## 限制

- 消息不落盘；消费方需幂等；
- 连接级投递接线 Phase 53（当前 broker + 默认队列订阅者）。
