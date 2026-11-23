package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.List;

/** GET key：返回 value；未命中返回 nil bulk。 */
public final class GetCommand implements Command {

    @Override
    public String name() {
        return "get";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return RespNull.BULK_STRING;
        }
        if (TypedValueCodec.typeOf(value) != ValueType.STRING) {
            return TypeSupport.wrongType();
        }
        return new RespBulkString(value);
    }
}
