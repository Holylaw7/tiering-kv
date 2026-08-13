package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;

import java.util.Arrays;
import java.util.List;

/** APPEND key value：原子追加，返回新长度（ADR-0269）。 */
public final class AppendCommand implements Command {

    @Override
    public String name() {
        return "append";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        int length;
        if (storage instanceof AtomicStringOps atomic) {
            length = atomic.append(args.get(0), args.get(1));
        } else {
            byte[] current = storage.get(args.get(0));
            byte[] base = current == null ? new byte[0] : current;
            byte[] merged = Arrays.copyOf(base,
                    base.length + args.get(1).length);
            System.arraycopy(args.get(1), 0, merged,
                    base.length, args.get(1).length);
            storage.put(args.get(0), merged);
            length = merged.length;
        }
        return new RespInteger(length);
    }
}
