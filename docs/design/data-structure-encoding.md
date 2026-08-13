# Data Structure Encoding

## 类型化值

```text
string : 裸字节（向后兼容）
hash   : TK 0x01 + payload
list   : TK 0x02 + payload
set    : TK 0x03 + payload
zset   : TK 0x04 + payload
```

## Payload

- Hash：字段数 + [fieldLen][field][valueLen][value]...（插入序）；
- List：元素数 + [len][bytes]...；
- Set：成员数 + [len][bytes]...（唯一）；
- ZSet：成员数 + [score:8B][len][member]...。

## 原子更新

`AtomicStringOps.update(key, transform)`：

- MemTable：段写锁内读旧值 → transform → 写新值（保留 TTL，
  null 删除）；
- WAL 装饰器：同步委托 + WAL-first。
