package io.tieringkv.command;

import io.tieringkv.observability.SlowLog;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

/** SLOWLOG GET [count] / LEN / RESET（M4-C1）。 */
public final class SlowLogCommand implements Command {

    private final SlowLog slowLog;

    public SlowLogCommand(SlowLog slowLog) {
        this.slowLog = Objects.requireNonNull(slowLog, "slowLog");
    }

    @Override
    public String name() {
        return "slowlog";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String sub = CommandUtil.text(args.get(0)).toLowerCase(Locale.ROOT);
        return switch (sub) {
            case "len" -> {
                if (args.size() != 1) {
                    yield RespError.wrongArity(name());
                }
                yield new RespInteger(slowLog.len());
            }
            case "reset" -> {
                if (args.size() != 1) {
                    yield RespError.wrongArity(name());
                }
                slowLog.reset();
                yield new RespSimpleString("OK");
            }
            case "get" -> {
                if (args.size() > 2) {
                    yield RespError.wrongArity(name());
                }
                int count = 10;
                if (args.size() == 2) {
                    try {
                        count = (int) CommandUtil.parseLong(args.get(1));
                    } catch (NumberFormatException e) {
                        yield new RespError(CommandUtil.NOT_INTEGER);
                    }
                    if (count < 0) {
                        yield new RespError("ERR count should be greater than or equal to 0");
                    }
                }
                List<RespValue> items = new ArrayList<>();
                for (SlowLog.Entry entry : slowLog.latest(count == 0 ? Integer.MAX_VALUE : count)) {
                    List<RespValue> cmdArgs = new ArrayList<>(entry.arguments().size());
                    for (String arg : entry.arguments()) {
                        cmdArgs.add(new RespBulkString(arg.getBytes(StandardCharsets.UTF_8)));
                    }
                    items.add(new RespArray(List.of(
                            new RespInteger(entry.id()),
                            new RespInteger(entry.timestampSeconds()),
                            new RespInteger(entry.durationMicros()),
                            new RespArray(cmdArgs))));
                }
                yield new RespArray(items);
            }
            default -> new RespError("ERR Unknown SLOWLOG subcommand '" + sub + "'");
        };
    }
}
