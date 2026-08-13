package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** TTL key / PTTL key：-2 不存在 / -1 无 TTL / ≥0 剩余（ADR-0270）。 */
public final class TtlCommand implements Command {

    private final String name;

    public TtlCommand(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name);
        }
        long millis;
        if (storage instanceof AtomicStringOps atomic) {
            millis = atomic.ttlMillis(args.get(0));
        } else {
            millis = storage.get(args.get(0)) == null ? -2 : -1;
        }
        long result = "ttl".equals(name)
                ? (millis < 0 ? millis : millis / 1000)
                : millis;
        return new RespInteger(result);
    }
}
