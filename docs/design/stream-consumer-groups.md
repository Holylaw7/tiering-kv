# Stream Consumer Groups

## 命令

| 命令 | 语义 |
| --- | --- |
| XGROUP key CREATE\|DESTROY group id | 组创建/销毁 |
| XREADGROUP GROUP g consumer STREAMS key > | 消费新消息 |
| XACK key group id... | 确认 |
| XPENDING key group | 未确认列表 |

## 持久化

组状态（last-delivered + pending）编码进 STREAM payload（additive
组段），旧数据兼容解码；重启恢复消费位置。
