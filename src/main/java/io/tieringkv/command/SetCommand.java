package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;

import java.util.List;

/** SET key value：写入并返回 OK。TTL / EX / PX 参数在 Phase 2 引入。 */
public final class SetCommand implements Command {

    @Override
    public String name() {
        return "set";
    }

    @Override
    public RespValue execute(List<byte[]> args, KVStore store) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        store.put(args.get(0), args.get(1));
        return new RespSimpleString("OK");
    }
}
