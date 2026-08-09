package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;

import java.util.List;

/** GET key：返回 value；未命中返回 nil bulk。 */
public final class GetCommand implements Command {

    @Override
    public String name() {
        return "get";
    }

    @Override
    public RespValue execute(List<byte[]> args, KVStore store) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = store.get(args.get(0));
        return value == null ? RespNull.BULK_STRING : new RespBulkString(value);
    }
}
