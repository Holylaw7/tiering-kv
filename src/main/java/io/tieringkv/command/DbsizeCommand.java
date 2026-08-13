package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** DBSIZE：返回键数量（ADR-0272）。 */
public final class DbsizeCommand implements Command {

    @Override
    public String name() {
        return "dbsize";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(storage.size());
    }
}
