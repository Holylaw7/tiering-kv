package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** SETNX key value：1 写入 / 0 已存在（ADR-0269）。 */
public final class SetNxCommand implements Command {

    @Override
    public String name() {
        return "setnx";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        boolean set;
        if (storage instanceof AtomicStringOps atomic) {
            set = atomic.putIfAbsent(args.get(0), args.get(1));
        } else {
            if (storage.exists(args.get(0))) {
                set = false;
            } else {
                storage.put(args.get(0), args.get(1));
                set = true;
            }
        }
        return new RespInteger(set ? 1 : 0);
    }
}
