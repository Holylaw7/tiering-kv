# Consumer Group Advanced Capabilities

## 命令

| 命令 | 语义 |
| --- | --- |
| XCLAIM key group consumer min-idle id... | 显式重新声明 |
| XAUTOCLAIM key group consumer min-idle start | 自动重新声明 |

重复投递同一消费者时 deadLetters 递增（additive 编码，旧数据
默认 0）。

## 限制

min-idle 语义简化（无空闲时间戳）；PEL 上限为后续方向。
