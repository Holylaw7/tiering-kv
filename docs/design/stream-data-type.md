# Stream Data Type

## 编码

STREAM 标签（TypedValueCodec 5）+ StreamCodec：

```text
[count][ms:8B][seq:8B][fieldCount][field][value]...
```

## 命令

| 命令 | 语义 |
| --- | --- |
| XADD key id field value... | 自增（ms-seq）/ 显式 id；旧 id 拒绝 |
| XREAD COUNT n STREAMS key id | id 之后条目 |
| XLEN key | 条目数 |
| XRANGE key start end [COUNT n] | id 范围 |
| XTRIM key MAXLEN n | 截断最旧，返回删除数 |

## 限制

- 整值重写 O(n)；无消费组（Phase 55+）。
