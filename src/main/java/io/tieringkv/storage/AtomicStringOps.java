package io.tieringkv.storage;

import java.util.function.UnaryOperator;

/**
 * 原子字符串操作（ADR-0269）：单键 read-modify-write 与 TTL 查询/
 * 修改。实现层（MemTable）在段写锁内完成；装饰器（WAL）以 WAL-first
 * 语义委托。不支持原子语义的引擎回退到 get/put（文档标注）。
 */
public interface AtomicStringOps {

    /** 原子自增/自减，返回新值；非整数或溢出抛 NumberFormatException /
     * ArithmeticException。 */
    long increment(byte[] key, long delta);

    /** 原子追加，返回新长度；保留 TTL。 */
    int append(byte[] key, byte[] value);

    /** 原子 SET 并返回旧值；清除 TTL（Redis GETSET 语义）。 */
    byte[] getSet(byte[] key, byte[] value);

    /** 原子替换并返回旧值；保留 TTL（INCR/APPEND 内部路径）。 */
    byte[] getAndSetPreservingTtl(byte[] key, byte[] value);

    /** 原子 GETDEL：返回旧值并删除。 */
    byte[] getDelete(byte[] key);

    /** 原子 SETNX：仅当 key 不存在时写入，返回是否写入。 */
    boolean putIfAbsent(byte[] key, byte[] value);

    /** TTL 剩余毫秒：-2 不存在 / -1 无 TTL / ≥0 剩余。 */
    long ttlMillis(byte[] key);

    /** 移除 TTL，返回是否移除。 */
    boolean persist(byte[] key);

    /** 设置绝对过期时间（毫秒），返回是否设置。 */
    boolean expireAt(byte[] key, long expireAtMillis);

    /** 原子更新：段锁内读旧值 -> 转换 -> 写新值（保留 TTL）；
     * transform 返回 null 表示删除。返回新值或 null。 */
    byte[] update(byte[] key, UnaryOperator<byte[]> transform);
}
