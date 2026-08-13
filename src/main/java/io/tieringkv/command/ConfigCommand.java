package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

/** CONFIG GET/SET：白名单配置（ADR-0272）。 */
public final class ConfigCommand implements Command {

    private static final Map<String, String> CONFIG =
            new ConcurrentHashMap<>(new TreeMap<>(Map.of(
                    "maxmemory", "1073741824",
                    "appendfsync", "everysec",
                    "timeout", "0",
                    "save", "3600 1",
                    "maxclients", "10000")));

    @Override
    public String name() {
        return "config";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 1) {
            return RespError.wrongArity(name());
        }
        String sub = CommandUtil.text(args.get(0))
                .toLowerCase(Locale.ROOT);
        switch (sub) {
            case "get" -> {
                if (args.size() != 2) {
                    return RespError.wrongArity(name());
                }
                String pattern = CommandUtil.text(args.get(1))
                        .toLowerCase(Locale.ROOT);
                List<RespValue> entries = new ArrayList<>();
                for (Map.Entry<String, String> entry
                        : CONFIG.entrySet()) {
                    if ("*".equals(pattern)
                            || entry.getKey().equals(pattern)
                            || ScanCommand.globMatches(pattern,
                            CommandUtil.bytes(
                                    entry.getKey()))) {
                        entries.add(new RespBulkString(
                                CommandUtil.bytes(entry.getKey())));
                        entries.add(new RespBulkString(
                                CommandUtil.bytes(entry.getValue())));
                    }
                }
                return new RespArray(entries);
            }
            case "set" -> {
                if (args.size() != 3) {
                    return RespError.wrongArity(name());
                }
                String param = CommandUtil.text(args.get(1))
                        .toLowerCase(Locale.ROOT);
                if (!CONFIG.containsKey(param)) {
                    return new RespError("ERR Unsupported CONFIG "
                            + "parameter: " + param);
                }
                CONFIG.put(param, CommandUtil.text(args.get(2)));
                return new RespSimpleString("OK");
            }
            default -> {
                return new RespError("ERR Unknown CONFIG "
                        + "subcommand or wrong number of "
                        + "arguments for '" + sub + "'");
            }
        }
    }
}
