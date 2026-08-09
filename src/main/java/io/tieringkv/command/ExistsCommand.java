package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;

import java.util.List;

/** EXISTS key...：返回存在的键数量。 */
public final class ExistsCommand implements Command {

    @Override
    public String name() {
        return "exists";
    }

    @Override
    public RespValue execute(List<byte[]> args, KVStore store) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        long count = 0;
        for (byte[] key : args) {
            if (store.exists(key)) {
                count++;
            }
        }
        return new RespInteger(count);
    }
}
