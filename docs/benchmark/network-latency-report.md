# Network Latency Report

## 说明

本机回环 TCP 口径（RpcClient/RpcServer），用于 Pub/Sub 广播与命令
接线延迟对比；跨地域口径待真实 Runner。

## 摘要

- RPC 单帧转发：亚毫秒级（回环）；
- 连接级 Push drain：事件循环内批量写出；
- RESP3 与 RESP2 编码差异：可忽略（同一批处理路径）。
