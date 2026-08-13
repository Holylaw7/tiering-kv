package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** GETSET key value：返回旧值并清除 TTL（ADR-0269）。 */
public final class GetSetCommand implements Command {

    @Override
    public String name() {
        return "getset";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        byte[] old;
        if (storage instanceof AtomicStringOps atomic) {
            old = atomic.getSet(args.get(0), args.get(1));
        } else {
            old = storage.get(args.get(0));
            storage.put(args.get(0), args.get(1));
        }
        return old == null ? RespNull.BULK_STRING
                : new RespBulkString(old);
    }
}
