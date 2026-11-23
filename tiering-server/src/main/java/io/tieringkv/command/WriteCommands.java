package io.tieringkv.command;

import java.util.Locale;
import java.util.Set;

/**
 * 写命令判定（TD-083）：内存超限时只拒绝写命令，读命令继续服务。
 *
 * <p>判定 = 显式清单 ∪ 名称启发式，后者用于覆盖后续新增的命令族
 * （例如 `json.set`、`ts.incrby`）。启发式只做保守扩展，误判方向是
 * "把可疑命令当写命令"，宁可多拒也不让堆被写满。
 */
public final class WriteCommands {

    private static final Set<String> EXPLICIT = Set.of(
            "set", "setnx", "setex", "psetex", "setrange", "append",
            "incr", "decr", "incrby", "decrby", "incrbyfloat",
            "getset", "getdel", "mset", "msetnx", "del", "unlink",
            "expire", "pexpire", "expireat", "pexpireat", "persist",
            "hset", "hmset", "hdel", "hincrby", "hsetnx",
            "lpush", "rpush", "lpop", "rpop", "lset", "lrem", "ltrim",
            "sadd", "srem", "spop",
            "zadd", "zrem", "zincrby", "zremrangebyrank", "zremrangebyscore",
            "flushdb", "flushall",
            "json.set", "json.del", "json.arrappend", "json.numincrby",
            "ts.add", "ts.incrby", "ts.del",
            "vector.add", "vector.del",
            "xadd", "xdel", "xtrim");

    /**
     * 释放内存的写命令：与 Redis 的 deny-oom 语义一致，内存超限时仍然放行，
     * 否则用户无法通过 DEL/FLUSHALL 自救。
     */
    private static final Set<String> FREEING = Set.of(
            "del", "unlink", "expire", "pexpire", "expireat", "pexpireat", "persist",
            "flushdb", "flushall",
            "hdel", "lrem", "ltrim", "lpop", "rpop", "srem", "spop",
            "zrem", "zremrangebyrank", "zremrangebyscore",
            "json.del", "vector.del", "ts.del", "xdel", "xtrim");

    private static final String[] HINTS = {
            "set", "add", "del", "rem", "push", "pop", "incr", "decr",
            "trim", "expire", "persist", "flush", "append"
    };

    /** 供测试与文档使用：内存超限时仍放行的写命令。 */
    public static Set<String> freeingCommands() {
        return FREEING;
    }

    private WriteCommands() {
    }

    public static boolean isWrite(String commandName) {
        if (commandName == null) {
            return false;
        }
        String name = commandName.toLowerCase(Locale.ROOT);
        if (EXPLICIT.contains(name)) {
            return true;
        }
        for (String hint : HINTS) {
            if (name.contains(hint)) {
                return true;
            }
        }
        return false;
    }

    /** 内存超限时是否应拒绝：写命令减去"释放型"写命令。 */
    public static boolean isRejectedUnderPressure(String commandName) {
        if (!isWrite(commandName)) {
            return false;
        }
        return !FREEING.contains(commandName.toLowerCase(Locale.ROOT));
    }
}
