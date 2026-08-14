package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/**
 * 多模型值命令族（ADR-0320）：JSON.SET/GET、TS.ADD/GET/LEN、
 * VECTOR.SET/GET，把 JSON/时序/向量作为类型化 KV 值存取。
 *
 * <p>与 M1 向量索引命令区分：VECTOR.SET/GET 操作 KV 类型化值，
 * VECTOR.ADD/SEARCH 操作 VectorIndexStore 检索索引。
 */
public final class MultiModelCommand implements Command {

    private final String name;
    private final VectorIndexStore vectorStore;

    public MultiModelCommand(String name) {
        this(name, null);
    }

    /**
     * @param vectorStore 可空；非空时 VECTOR.SET 同步写入 M1 检索索引
     *                    （ADR-0320 自动索引接线）。
     */
    public MultiModelCommand(String name,
                             VectorIndexStore vectorStore) {
        this.name = name;
        this.vectorStore = vectorStore;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        return switch (name) {
            case "json.set" -> jsonSet(args, storage);
            case "json.get" -> jsonGet(args, storage);
            case "ts.add" -> tsAdd(args, storage);
            case "ts.get" -> tsGet(args, storage);
            case "ts.len" -> tsLen(args, storage);
            case "vector.set" -> vectorSet(args, storage);
            case "vector.get" -> vectorGet(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue jsonSet(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        storage.put(args.get(0), MultiModelCodec.encodeJson(
                text(args.get(1))));
        return new RespSimpleString("OK");
    }

    private RespValue jsonGet(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return RespNull.BULK_STRING;
        }
        if (TypedValueCodec.typeOf(value) != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        return MultiModelCodec.jsonToResp(value);
    }

    private RespValue tsAdd(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name());
        }
        long timestamp;
        double sample;
        try {
            timestamp = Long.parseLong(text(args.get(1)));
            sample = Double.parseDouble(text(args.get(2)));
        } catch (NumberFormatException e) {
            return new RespError("ERR invalid TS.ADD arguments");
        }
        List<MultiModelCodec.TimePoint> points;
        byte[] existing = storage.get(args.get(0));
        if (existing == null) {
            points = new ArrayList<>();
        } else {
            if (TypedValueCodec.typeOf(existing)
                    != ValueType.TIME_SERIES) {
                return TypeSupport.wrongType();
            }
            points = new ArrayList<>(
                    MultiModelCodec.decodeTimeSeries(existing));
        }
        points.add(new MultiModelCodec.TimePoint(timestamp, sample));
        storage.put(args.get(0),
                MultiModelCodec.encodeTimeSeries(points));
        return new RespSimpleString("OK");
    }

    private RespValue tsGet(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return RespNull.ARRAY;
        }
        if (TypedValueCodec.typeOf(value)
                != ValueType.TIME_SERIES) {
            return TypeSupport.wrongType();
        }
        return MultiModelCodec.timeSeriesToResp(value);
    }

    private RespValue tsLen(List<byte[]> args,
                            StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return new RespInteger(0);
        }
        if (TypedValueCodec.typeOf(value)
                != ValueType.TIME_SERIES) {
            return TypeSupport.wrongType();
        }
        return new RespInteger(
                MultiModelCodec.decodeTimeSeries(value).size());
    }

    private RespValue vectorSet(List<byte[]> args,
                                StorageEngine storage) {
        if (args.size() < 3) {
            return RespError.wrongArity(name());
        }
        int dim;
        try {
            dim = Integer.parseInt(text(args.get(1)));
        } catch (NumberFormatException e) {
            return new RespError("ERR invalid VECTOR.SET arguments");
        }
        if (dim < 1 || args.size() != 2 + dim) {
            return new RespError("ERR invalid VECTOR.SET arguments");
        }
        float[] values = new float[dim];
        for (int i = 0; i < dim; i++) {
            try {
                values[i] = Float.parseFloat(text(args.get(2 + i)));
            } catch (NumberFormatException e) {
                return new RespError(
                        "ERR invalid vector value at index " + i);
            }
        }
        storage.put(args.get(0),
                MultiModelCodec.encodeVector(values));
        if (vectorStore != null) {
            vectorStore.put(new Embedding(text(args.get(0)),
                    values));
        }
        return new RespSimpleString("OK");
    }

    private RespValue vectorGet(List<byte[]> args,
                                StorageEngine storage) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        byte[] value = storage.get(args.get(0));
        if (value == null) {
            return RespNull.ARRAY;
        }
        if (TypedValueCodec.typeOf(value) != ValueType.VECTOR) {
            return TypeSupport.wrongType();
        }
        return MultiModelCodec.vectorToResp(value);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
