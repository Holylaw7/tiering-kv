# Data Structure Latency Report

## 说明

本地进程内口径；数据结构操作为整值重写 O(size)，延迟随结构大小
线性增长（文档登记）。

## 摘要

- Hash/Set 小结构：微秒级；
- List 头尾：O(n) 重写（索引 O(1)）；
- ZSet：每次排序 O(n log n)；
- 原子性：段锁内 update，无 lost update（并发矩阵覆盖）。
