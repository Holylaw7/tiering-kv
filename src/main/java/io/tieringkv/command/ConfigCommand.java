package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespMap;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/** CONFIG GET/SET：对接运行期实时参数（内存、WAL 刷盘、连接上限、空闲超时与 requirepass）。 */
public final class ConfigCommand implements Command {

    private final RuntimeConfigBridge bridge;

    public ConfigCommand() {
        this(RuntimeConfigBridge.standaloneDefaults());
    }

    public ConfigCommand(RuntimeConfigBridge bridge) {
        this.bridge = Objects.requireNonNull(bridge, "bridge");
    }

    @Override
    public String name() {
        return "config";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String sub = CommandUtil.text(args.get(0)).toLowerCase(Locale.ROOT);
        switch (sub) {
            case "get" -> {
                if (args.size() != 2) {
                    return RespError.wrongArity(name());
                }
                String pattern = CommandUtil.text(args.get(1)).toLowerCase(Locale.ROOT);
                List<RespValue> entries = new ArrayList<>();
                for (Map.Entry<String, String> entry : bridge.snapshot().entrySet()) {
                    if ("*".equals(pattern)
                            || entry.getKey().equals(pattern)
                            || ScanCommand.globMatches(pattern, CommandUtil.bytes(entry.getKey()))) {
                        entries.add(new RespBulkString(CommandUtil.bytes(entry.getKey())));
                        entries.add(new RespBulkString(CommandUtil.bytes(entry.getValue())));
                    }
                }
                ConnectionContext context = ConnectionContext.current();
                if (context != null && context.version() == RespVersion.RESP3) {
                    return new RespMap(entries);
                }
                return new RespArray(entries);
            }
            case "set" -> {
                if (args.size() != 3) {
                    return RespError.wrongArity(name());
                }
                String param = CommandUtil.text(args.get(1)).toLowerCase(Locale.ROOT);
                if (!bridge.supports(param)) {
                    return new RespError("ERR Unsupported CONFIG parameter: " + param);
                }
                try {
                    bridge.set(param, CommandUtil.text(args.get(2)));
                    return new RespSimpleString("OK");
                } catch (IllegalArgumentException ex) {
                    return new RespError("ERR " + ex.getMessage());
                }
            }
            default -> {
                return new RespError("ERR Unknown CONFIG subcommand or wrong number of arguments for '" + sub + "'");
            }
        }
    }
}
