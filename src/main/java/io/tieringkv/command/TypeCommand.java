package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** TYPE key：当前仅字符串类型（ADR-0272）。 */
public final class TypeCommand implements Command {

    @Override
    public String name() {
        return "type";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        return new RespSimpleString(
                storage.exists(args.get(0)) ? "string" : "none");
    }
}
