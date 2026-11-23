package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;

/** MGET key...：批量读，缺失元素为 nil（ADR-0271）。 */
public final class MgetCommand implements Command {

    @Override
    public String name() {
        return "mget";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        List<RespValue> values = new ArrayList<>(args.size());
        for (byte[] key : args) {
            byte[] value = storage.get(key);
            values.add(value == null ? RespNull.BULK_STRING
                    : new RespBulkString(value));
        }
        return new RespArray(values);
    }
}
