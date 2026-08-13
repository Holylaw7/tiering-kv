package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** EXPIRE/PEXPIRE/EXPIREAT/PEXPIREAT key ttl：设置过期（ADR-0270）。 */
public final class ExpireCommand implements Command {

    private final String name;
    private final boolean absolute;
    private final long multiplier;

    public ExpireCommand(String name, boolean absolute,
                         long multiplier) {
        this.name = name;
        this.absolute = absolute;
        this.multiplier = multiplier;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        try {
            long parsed = CommandUtil.parseLong(args.get(1));
            long absoluteMillis = absolute
                    ? Math.multiplyExact(parsed, multiplier)
                    : Math.addExact(System.currentTimeMillis(),
                    Math.multiplyExact(parsed, multiplier));
            boolean ok;
            if (storage instanceof AtomicStringOps atomic) {
                ok = atomic.expireAt(args.get(0), absoluteMillis);
            } else {
                byte[] value = storage.get(args.get(0));
                if (value == null) {
                    ok = false;
                } else {
                    long ttl = Math.max(0, absoluteMillis
                            - System.currentTimeMillis());
                    storage.put(args.get(0), value, ttl);
                    ok = true;
                }
            }
            return new RespInteger(ok ? 1 : 0);
        } catch (NumberFormatException | ArithmeticException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }
}
