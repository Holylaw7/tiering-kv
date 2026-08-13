# RESP3 Support

## 新类型

| 类型 | 前缀 | 表达 |
| --- | --- | --- |
| Map | % | 键值平铺 |
| Set | ~ | 集合 |
| Double | , | 浮点 |
| BigNumber | ( | 大整数 |
| Push | > | 推送消息 |

## 版本协商

- 默认 RESP2（ConnectionProtocolState）；
- `HELLO 3` 切换 RESP3；`HELLO 2` 回退；
- `RespEncoder.writeV3` 原生编码；`write` 保持 RESP2 回退（Map→
  数组等）。

## 接线状态

连接级状态类已提供；网络管道接线 Phase 53 完成（本阶段命令层 +
编码矩阵全绿）。
