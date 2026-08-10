package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** ECHO：回显唯一参数。 */
public final class EchoCommand implements Command {

    @Override
    public String name() {
        return "echo";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        return new RespBulkString(args.get(0));
    }
}
