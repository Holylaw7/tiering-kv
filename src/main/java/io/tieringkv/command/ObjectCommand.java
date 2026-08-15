package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * OBJECT 命令族（ADR-0340）：ENCODING/REFCOUNT/IDLETIME/FREQ。
 *
 * <p>ENCODING 以项目类型系统为准（Redis 内部编码口径有差异，文档
 * 登记）：string 短值 embstr/长值 raw，复合类型 hashtable/
 * quicklist/skiplist/stream，多模型 json/timeseries/vector；
 * REFCOUNT=1、IDLETIME=0（无 LRU 跟踪）、FREQ=-1（无 LFU 暴露）。
 */
public final class ObjectCommand implements Command {

    private static final int EMBSTR_MAX_BYTES = 44;

    @Override
    public String name() {
        return "object";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String subcommand = text(args.get(0))
                .toLowerCase(Locale.ROOT);
        if (args.size() != 2 && (subcommand.equals("encoding")
                || subcommand.equals("refcount")
                || subcommand.equals("idletime")
                || subcommand.equals("freq"))) {
            return RespError.wrongArity(name());
        }
        return switch (subcommand) {
            case "encoding" -> encoding(storage, args.get(1));
            case "refcount" -> simpleInteger(storage, args.get(1), 1);
            case "idletime" -> simpleInteger(storage, args.get(1), 0);
            case "freq" -> simpleInteger(storage, args.get(1), -1);
            default -> new RespError("ERR Unknown subcommand or "
                    + "wrong number of arguments for 'OBJECT'");
        };
    }

    private static RespValue encoding(StorageEngine storage,
                                      byte[] key) {
        byte[] value = storage.get(key);
        if (value == null) {
            return RespNull.BULK_STRING;
        }
        String encoding = switch (TypedValueCodec.typeOf(value)) {
            case STRING -> value.length <= EMBSTR_MAX_BYTES
                    ? "embstr" : "raw";
            case HASH -> "hashtable";
            case LIST -> "quicklist";
            case SET -> "hashtable";
            case ZSET -> "skiplist";
            case STREAM -> "stream";
            case JSON -> "json";
            case TIME_SERIES -> "timeseries";
            case VECTOR -> "vector";
        };
        return new RespBulkString(encoding.getBytes(
                StandardCharsets.UTF_8));
    }

    private static RespValue simpleInteger(StorageEngine storage,
                                           byte[] key, int value) {
        return storage.get(key) == null
                ? RespNull.BULK_STRING : new RespInteger(value);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
