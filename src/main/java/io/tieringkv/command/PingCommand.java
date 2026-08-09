package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;

import java.util.List;

/** PING：无参数返回 PONG，一个参数回显 bulk string（Redis 兼容）。 */
public final class PingCommand implements Command {

    @Override
    public String name() {
        return "ping";
    }

    @Override
    public RespValue execute(List<byte[]> args, KVStore store) {
        if (args.isEmpty()) {
            return new RespSimpleString("PONG");
        }
        if (args.size() == 1) {
            return new RespBulkString(args.get(0));
        }
        return RespError.wrongArity(name());
    }
}
