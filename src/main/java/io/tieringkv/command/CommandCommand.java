package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/** COMMAND COUNT/INFO：注册表命令元数据（ADR-0272）。 */
public final class CommandCommand implements Command {

    /** 命令元数据：arity（含命令名）、flags、firstKey、lastKey、step。 */
    private static final Map<String, Object[]> METADATA = Map.ofEntries(
            Map.entry("get", new Object[]{2, "readonly", 1, 1, 1}),
            Map.entry("set", new Object[]{-3, "write", 1, 1, 1}),
            Map.entry("del", new Object[]{-2, "write", 1, -1, 1}),
            Map.entry("exists", new Object[]{-2, "readonly", 1, -1, 1}),
            Map.entry("incr", new Object[]{2, "write", 1, 1, 1}),
            Map.entry("decr", new Object[]{2, "write", 1, 1, 1}),
            Map.entry("incrby", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("decrby", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("append", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("strlen", new Object[]{2, "readonly", 1, 1, 1}),
            Map.entry("getset", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("setnx", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("setex", new Object[]{4, "write", 1, 1, 1}),
            Map.entry("psetex", new Object[]{4, "write", 1, 1, 1}),
            Map.entry("getdel", new Object[]{2, "write", 1, 1, 1}),
            Map.entry("getrange", new Object[]{4, "readonly", 1, 1, 1}),
            Map.entry("setrange", new Object[]{4, "write", 1, 1, 1}),
            Map.entry("ttl", new Object[]{2, "readonly", 1, 1, 1}),
            Map.entry("pttl", new Object[]{2, "readonly", 1, 1, 1}),
            Map.entry("expire", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("pexpire", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("expireat", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("pexpireat", new Object[]{3, "write", 1, 1, 1}),
            Map.entry("persist", new Object[]{2, "write", 1, 1, 1}),
            Map.entry("mget", new Object[]{-2, "readonly", 1, -1, 1}),
            Map.entry("mset", new Object[]{-3, "write", 1, -1, 2}),
            Map.entry("msetnx", new Object[]{-3, "write", 1, -1, 2}),
            Map.entry("dbsize", new Object[]{1, "readonly", 0, 0, 0}),
            Map.entry("flushdb", new Object[]{1, "write", 0, 0, 0}),
            Map.entry("flushall", new Object[]{1, "write", 0, 0, 0}),
            Map.entry("scan", new Object[]{-2, "readonly", 0, 0, 0}),
            Map.entry("type", new Object[]{2, "readonly", 1, 1, 1}),
            Map.entry("config", new Object[]{-2, "admin", 0, 0, 0}),
            Map.entry("client", new Object[]{-2, "admin", 0, 0, 0}),
            Map.entry("command", new Object[]{-1, "readonly", 0, 0, 0}));

    private static final java.util.Set<String> READONLY =
            java.util.Set.of("hget", "hexists", "hlen", "hkeys",
                    "hvals", "hgetall", "hmget", "llen", "lrange",
                    "lindex", "scard", "smembers", "sismember",
                    "srandmember", "sinter", "sunion", "sdiff",
                    "zscore", "zrange", "zrevrange", "zcard",
                    "zrangebyscore", "zcount", "zrank", "zrevrank");

    private final CommandRegistry registry;

    public CommandCommand(CommandRegistry registry) {
        this.registry = registry;
    }

    @Override
    public String name() {
        return "command";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String sub = CommandUtil.text(args.get(0))
                .toLowerCase(java.util.Locale.ROOT);
        switch (sub) {
            case "count" -> {
                return new RespInteger(registry.size());
            }
            case "info" -> {
                List<RespValue> result = new ArrayList<>();
                for (int i = 1; i < args.size(); i++) {
                    String commandName = CommandUtil.text(args.get(i))
                            .toLowerCase(java.util.Locale.ROOT);
                    Object[] meta = metadataFor(commandName);
                    if (meta == null) {
                        result.add(RespNull.ARRAY);
                    } else {
                        result.add(new RespArray(List.of(
                                new RespBulkString(CommandUtil.bytes(
                                        commandName)),
                                new RespInteger((int) meta[0]),
                                new RespArray(List.of(
                                        new RespBulkString(
                                                CommandUtil.bytes(
                                                        (String) meta[1])))),
                                new RespInteger((int) meta[2]),
                                new RespInteger((int) meta[3]),
                                new RespInteger((int) meta[4]))));
                    }
                }
                return new RespArray(result);
            }
            default -> {
                return new RespError("ERR unknown subcommand '"
                        + sub + "'");
            }
        }
    }

    private Object[] metadataFor(String commandName) {
        Object[] explicit = METADATA.get(commandName);
        if (explicit != null) {
            return explicit;
        }
        if (registry.find(commandName) == null) {
            return null;
        }
        String flags = READONLY.contains(commandName)
                ? "readonly" : "write";
        return new Object[]{-2, flags, 1, 1, 1};
    }
}
