package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** SETEX key seconds value / PSETEX key ms value（ADR-0269）。 */
public final class SetExCommand implements Command {

    private final String name;
    private final long multiplier;

    public SetExCommand(String name, long multiplier) {
        this.name = name;
        this.multiplier = multiplier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        try {
            long ttlMillis = Math.multiplyExact(
                    CommandUtil.parseLong(args.get(1)),
                    multiplier);
            storage.put(args.get(0), args.get(2), ttlMillis);
            return new RespSimpleString("OK");
        } catch (NumberFormatException | ArithmeticException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }
}
