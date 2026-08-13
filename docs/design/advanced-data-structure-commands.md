# Advanced Data Structure Commands

## 命令

| 命令 | 语义 |
| --- | --- |
| HSCAN key cursor [MATCH p] [COUNT n] | 字段快照遍历（单页） |
| LINSERT key BEFORE\|AFTER pivot value | 枢轴插入；缺失键 0 / 无枢轴 -1 |
| LMOVE src dst LEFT\|RIGHT LEFT\|RIGHT | 源弹目标推（顺序执行） |
| RPOPLPUSH src dst | LMOVE RIGHT LEFT |
| ZRANGEBYLEX key min max | 字典序范围（[、(、-、+） |
| ZLEXCOUNT key min max | 范围计数 |
| ZREMRANGEBYLEX key min max | 范围删除（空删键） |

## 原子性

单键走 `AtomicStringOps.update`；LMOVE/RPOPLPUSH 双键为顺序执行
（跨键原子性文档登记）。
