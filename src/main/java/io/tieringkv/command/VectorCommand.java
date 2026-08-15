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
import io.tieringkv.vector.collection.VectorCollectionRegistry;
import io.tieringkv.vector.indexfile.VectorIndexStore;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 向量命令族（ADR-0319/0338）：VECTOR.ADD/SEARCH/DEL/LEN（支持可选
 * `COLLECTION <name>` 前缀，缺省默认集合）+ VECTOR.LIST/DROP/
 * CHECKPOINT。
 *
 * <p>语法：
 * <ul>
 *   <li>VECTOR.ADD [COLLECTION c] &lt;id&gt; &lt;dim&gt; &lt;f...&gt;</li>
 *   <li>VECTOR.SEARCH [COLLECTION c] &lt;dim&gt; &lt;f...&gt; [TOPK n]</li>
 *   <li>VECTOR.DEL [COLLECTION c] &lt;id&gt;</li>
 *   <li>VECTOR.LEN [COLLECTION c]</li>
 *   <li>VECTOR.LIST / VECTOR.DROP &lt;c&gt; /
 *       VECTOR.CHECKPOINT [COLLECTION c]</li>
 * </ul>
 * 默认注册表不含本族；通过
 * {@link CommandRegistry#createDefaultWithVector} 显式启用。
 */
public final class VectorCommand implements Command {

    private final String name;
    private final VectorCollectionRegistry registry;

    public VectorCommand(String name, VectorIndexStore store) {
        this(name, VectorCollectionRegistry.ofDefault(store));
    }

    public VectorCommand(String name,
                         VectorCollectionRegistry registry) {
        if (registry == null) {
            throw new IllegalArgumentException("registry required");
        }
        this.name = name;
        this.registry = registry;
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
            case "vector.list" -> list(args);
            case "vector.drop" -> drop(args);
            case "vector.checkpoint" -> checkpoint(args);
            default -> new RespError(
                    "ERR unknown vector subcommand '" + name + "'");
        };
    }

    private RespValue add(List<byte[]> args) {
        CollectionArgs parsed = parseCollection(args);
        List<byte[]> rest = args.subList(parsed.offset(),
                args.size());
        if (rest.size() < 3) {
            return RespError.wrongArity(name());
        }
        String id = text(rest.get(0));
        int dim = parseInt(rest.get(1));
        if (dim < 1 || rest.size() != 2 + dim) {
            return new RespError(
                    "ERR invalid VECTOR.ADD arguments");
        }
        float[] values = new float[dim];
        for (int i = 0; i < dim; i++) {
            values[i] = parseFloat(rest.get(2 + i));
            if (Float.isNaN(values[i])) {
                return new RespError(
                        "ERR invalid vector value at index " + i);
            }
        }
        registry.put(parsed.collection(),
                new Embedding(id, values));
        return new RespSimpleString("OK");
    }

    private RespValue search(List<byte[]> args) {
        CollectionArgs parsed = parseCollection(args);
        List<byte[]> rest = args.subList(parsed.offset(),
                args.size());
        if (rest.size() < 2) {
            return RespError.wrongArity(name());
        }
        VectorIndexStore target =
                registry.collectionIfPresent(parsed.collection());
        if (target == null) {
            return new RespArray(List.of());
        }
        int dim = parseInt(rest.get(0));
        int topK = 10;
        int valueCount = rest.size() - 1;
        if (valueCount >= 2
                && asciiEquals(rest.get(rest.size() - 2), "TOPK")) {
            topK = parseInt(rest.get(rest.size() - 1));
            valueCount -= 2;
        }
        if (dim < 1 || valueCount != dim || topK < 0) {
            return new RespError(
                    "ERR invalid VECTOR.SEARCH arguments");
        }
        float[] query = new float[dim];
        for (int i = 0; i < dim; i++) {
            query[i] = parseFloat(rest.get(1 + i));
            if (Float.isNaN(query[i])) {
                return new RespError(
                        "ERR invalid query value at index " + i);
            }
        }
        List<VectorStore.ScoredEmbedding> results =
                target.store().search(query, topK);
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
        CollectionArgs parsed = parseCollection(args);
        if (args.size() != parsed.offset() + 1) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(registry.delete(parsed.collection(),
                text(args.get(parsed.offset()))) ? 1 : 0);
    }

    private RespValue len(List<byte[]> args) {
        CollectionArgs parsed = parseCollection(args);
        if (args.size() != parsed.offset()) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(
                registry.size(parsed.collection()));
    }

    private RespValue list(List<byte[]> args) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        List<RespValue> items = new ArrayList<>();
        for (String collection : registry.names()) {
            items.add(new RespArray(List.of(
                    new RespBulkString(collection.getBytes(
                            StandardCharsets.UTF_8)),
                    new RespInteger(registry.size(collection)))));
        }
        return new RespArray(items);
    }

    private RespValue drop(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        return new RespInteger(
                registry.drop(text(args.get(0))) ? 1 : 0);
    }

    private RespValue checkpoint(List<byte[]> args) {
        CollectionArgs parsed = parseCollection(args);
        if (args.size() != parsed.offset()) {
            return RespError.wrongArity(name());
        }
        try {
            if (parsed.offset() == 2) {
                registry.checkpoint(parsed.collection(), null);
            } else {
                registry.checkpointAll(null);
            }
            return new RespSimpleString("OK");
        } catch (IOException e) {
            return new RespError("ERR " + e.getMessage());
        }
    }

    private CollectionArgs parseCollection(List<byte[]> args) {
        if (args.size() >= 2
                && asciiEquals(args.get(0), "COLLECTION")) {
            return new CollectionArgs(text(args.get(1)), 2);
        }
        return new CollectionArgs(
                VectorCollectionRegistry.DEFAULT_COLLECTION, 0);
    }

    private record CollectionArgs(String collection, int offset) {
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
        return upper.equals(text(value).toUpperCase(Locale.ROOT));
    }
}
