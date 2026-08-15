package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * ACL 命令族（ADR-0340）：只读子集 WHOAMI/LIST/CAT/GETUSER。
 *
 * <p>当前为单默认用户（default：on nopass ~* &* +@all）；
 * SETUSER 暂缓（文档登记）；ACL CAT <category> 暂不枚举命令
 * （返回空数组）。
 */
public final class AclCommand implements Command {

    private static final Set<String> CATEGORIES = Set.of(
            "generic", "string", "list", "set", "zset", "hash",
            "stream", "pubsub", "transaction", "connection", "server",
            "scripting", "json", "timeseries", "vector");

    private static final List<String> DEFAULT_USER_RULES = List.of(
            "on", "nopass", "~*", "&*", "+@all");

    @Override
    public String name() {
        return "acl";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String subcommand = text(args.get(0))
                .toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "whoami" -> whoami(args);
            case "list" -> list(args);
            case "cat" -> cat(args);
            case "getuser" -> getuser(args);
            default -> new RespError(
                    "ERR Unknown ACL subcommand");
        };
    }

    private static RespValue whoami(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity("acl");
        }
        return bulk("default");
    }

    private static RespValue list(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity("acl");
        }
        return new RespArray(List.of(bulk(
                "user default on nopass ~* &* +@all")));
    }

    private static RespValue cat(List<byte[]> args) {
        if (args.size() > 2) {
            return RespError.wrongArity("acl");
        }
        if (args.size() == 2) {
            String category = text(args.get(1))
                    .toLowerCase(Locale.ROOT);
            if (!CATEGORIES.contains(category)) {
                return new RespError("ERR Unknown category '"
                        + category + "'");
            }
            return new RespArray(List.of()); // 命令枚举暂缓
        }
        List<RespValue> categories = new ArrayList<>();
        CATEGORIES.stream().sorted().forEach(
                category -> categories.add(bulk(category)));
        return new RespArray(categories);
    }

    private static RespValue getuser(List<byte[]> args) {
        if (args.size() != 2) {
            return RespError.wrongArity("acl");
        }
        if (!text(args.get(1)).equals("default")) {
            return new RespError("ERR User '"
                    + text(args.get(1)) + "' not found");
        }
        List<RespValue> rules = new ArrayList<>();
        DEFAULT_USER_RULES.forEach(rule -> rules.add(bulk(rule)));
        return new RespArray(rules);
    }

    private static RespBulkString bulk(String value) {
        return new RespBulkString(value.getBytes(
                StandardCharsets.UTF_8));
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
