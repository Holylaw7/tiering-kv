package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** PERSIST key：移除 TTL，返回 1/0（ADR-0270）。 */
public final class PersistCommand implements Command {

    @Override
    public String name() {
        return "persist";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        boolean ok;
        if (storage instanceof AtomicStringOps atomic) {
            ok = atomic.persist(args.get(0));
        } else {
            byte[] value = storage.get(args.get(0));
            if (value == null) {
                ok = false;
            } else {
                storage.put(args.get(0), value);
                ok = true;
            }
        }
        return new RespInteger(ok ? 1 : 0);
    }
}
