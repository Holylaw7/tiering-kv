package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 向量命令族（ADR-0319）：VECTOR.ADD / VECTOR.SEARCH / VECTOR.DEL /
 * VECTOR.LEN，通过注入的 VectorIndexStore 提供 Redis 协议入口。
 *
 * <p>语法：
 * <ul>
 *   <li>VECTOR.ADD &lt;id&gt; &lt;dim&gt; &lt;f...&gt;</li>
 *   <li>VECTOR.SEARCH &lt;dim&gt; &lt;f...&gt; [TOPK &lt;n&gt;]</li>
 *   <li>VECTOR.DEL &lt;id&gt;</li>
 *   <li>VECTOR.LEN</li>
 * </ul>
 * 默认注册表（115 命令）不含本族；通过
 * {@link CommandRegistry#createDefaultWithVector} 显式启用。
 */
public final class VectorCommand implements Command {

    private final String name;
    private final VectorIndexStore store;

    public VectorCommand(String name, VectorIndexStore store) {
        if (store == null) {
            throw new IllegalArgumentException("store required");
        }
        this.name = name;
        this.store = store;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        return switch (name) {
            case "vector.add" -> add(args);
            case "vector.search" -> search(args);
            case "vector.del" -> del(args);
            case "vector.len" -> len(args);
            default -> new RespError(
                    "ERR unknown vector subcommand '" + name + "'");
        };
    }

    private RespValue add(List<byte[]> args) {
        if (args.size() < 3) {
            return RespError.wrongArity(name());
        }
        String id = text(args.get(0));
        int dim = parseInt(args.get(1));
        if (dim < 1 || args.size() != 2 + dim) {
            return new RespError(
                    "ERR invalid VECTOR.ADD arguments");
        }
        float[] values = new float[dim];
        for (int i = 0; i < dim; i++) {
            values[i] = parseFloat(args.get(2 + i));
            if (Float.isNaN(values[i])) {
                return new RespError(
                        "ERR invalid vector value at index " + i);
            }
        }
        store.put(new Embedding(id, values));
        return new RespSimpleString("OK");
    }

    private RespValue search(List<byte[]> args) {
        if (args.size() < 2) {
            return RespError.wrongArity(name());
        }
        int dim = parseInt(args.get(0));
        int topK = 10;
        int valueCount = args.size() - 1;
        if (valueCount >= 2
                && asciiEquals(args.get(args.size() - 2), "TOPK")) {
            topK = parseInt(args.get(args.size() - 1));
            valueCount -= 2;
        }
        if (dim < 1 || valueCount != dim || topK < 0) {
            return new RespError(
                    "ERR invalid VECTOR.SEARCH arguments");
        }
        float[] query = new float[dim];
        for (int i = 0; i < dim; i++) {
            query[i] = parseFloat(args.get(1 + i));
            if (Float.isNaN(query[i])) {
                return new RespError(
                        "ERR invalid query value at index " + i);
            }
        }
        List<VectorStore.ScoredEmbedding> results =
                store.store().search(query, topK);
        List<RespValue> items = new ArrayList<>();
        for (VectorStore.ScoredEmbedding result : results) {
            items.add(new RespArray(List.of(
                    new RespBulkString(result.id().getBytes(
                            StandardCharsets.UTF_8)),
                    new RespDouble(result.score()))));
        }
        return new RespArray(items);
    }

    private RespValue del(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(
                store.delete(text(args.get(0))) ? 1 : 0);
    }

    private RespValue len(List<byte[]> args) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(store.size());
    }

    private static int parseInt(byte[] value) {
        try {
            return Integer.parseInt(text(value));
        } catch (NumberFormatException e) {
            return -1;
        }
    }

    private static float parseFloat(byte[] value) {
        try {
            return Float.parseFloat(text(value));
        } catch (NumberFormatException e) {
            return Float.NaN;
        }
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }

    private static boolean asciiEquals(byte[] value, String upper) {
        String text = text(value).toUpperCase(Locale.ROOT);
        return upper.equals(text);
    }
}
