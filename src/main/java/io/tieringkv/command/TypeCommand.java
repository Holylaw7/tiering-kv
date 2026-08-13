package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;

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
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return new RespSimpleString("none");
        }
        return new RespSimpleString(switch (
                TypedValueCodec.typeOf(value)) {
            case STRING -> "string";
            case HASH -> "hash";
            case LIST -> "list";
            case SET -> "set";
            case ZSET -> "zset";
            case STREAM -> "stream";
        });
    }
}
