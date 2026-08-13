package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** GETDEL key：返回旧值并删除（ADR-0269）。 */
public final class GetDelCommand implements Command {

    @Override
    public String name() {
        return "getdel";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] old;
        if (storage instanceof AtomicStringOps atomic) {
            old = atomic.getDelete(args.get(0));
        } else {
            old = storage.get(args.get(0));
            storage.delete(args.get(0));
        }
        return old == null ? RespNull.BULK_STRING
                : new RespBulkString(old);
    }
}
