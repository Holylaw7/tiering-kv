package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;

import java.util.List;

/** DEL key...：返回实际删除的键数量。 */
public final class DelCommand implements Command {

    @Override
    public String name() {
        return "del";
    }

    @Override
    public RespValue execute(List<byte[]> args, KVStore store) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        long removed = 0;
        for (byte[] key : args) {
            removed += store.delete(key);
        }
        return new RespInteger(removed);
    }
}
