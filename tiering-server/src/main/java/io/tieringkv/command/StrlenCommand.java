package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.util.List;

/** STRLEN key：缺失返回 0（ADR-0269）。 */
public final class StrlenCommand implements Command {

    @Override
    public String name() {
        return "strlen";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value != null
                && TypedValueCodec.typeOf(value)
                != ValueType.STRING) {
            return TypeSupport.wrongType();
        }
        return new RespInteger(value == null ? 0 : value.length);
    }
}
