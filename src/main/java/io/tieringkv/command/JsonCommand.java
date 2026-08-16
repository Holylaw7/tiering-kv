package io.tieringkv.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.NullNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.observability.MultiModelMetricsRegistry;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.types.TypedValueCodec;
import io.tieringkv.storage.types.ValueType;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.function.Function;

/**
 * JSON 路径命令族（ADR-0336）：JSON.SET/GET/DEL/TYPE/ARRAPPEND/
 * ARRLEN/OBJKEYS/OBJLEN/STRLEN/NUMINCRBY。
 *
 * <p>解析/序列化由 Jackson 负责；路径语义见 {@link JsonPath}；
 * 变更命令经 TypeSupport.update 原子执行并保留 TTL。
 */
public final class JsonCommand implements Command {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private final String name;
    private final MultiModelMetricsRegistry metrics;

    public JsonCommand(String name) {
        this(name, null);
    }

    /** 多模型喂数（ADR-0345）：可选指标注册表（additive）。 */
    public JsonCommand(String name,
                       MultiModelMetricsRegistry metrics) {
        this.name = name;
        this.metrics = metrics;
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
            case "json.del" -> jsonDel(args, storage);
            case "json.type" -> jsonType(args, storage);
            case "json.arrappend" -> jsonArrAppend(args, storage);
            case "json.arrlen" -> jsonArrLen(args, storage);
            case "json.objkeys" -> jsonObjKeys(args, storage);
            case "json.objlen" -> jsonObjLen(args, storage);
            case "json.strlen" -> jsonStrLen(args, storage);
            case "json.numincrby" -> jsonNumIncrBy(args, storage);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue jsonSet(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() < 2 || args.size() > 4) {
            return RespError.wrongArity(name);
        }
        String path = "$";
        String value;
        boolean nx = false;
        boolean xx = false;
        if (args.size() == 2) {
            value = text(args.get(1));
        } else {
            path = text(args.get(1));
            value = text(args.get(2));
            if (args.size() == 4) {
                String condition = text(args.get(3))
                        .toLowerCase(Locale.ROOT);
                if (condition.equals("nx")) {
                    nx = true;
                } else if (condition.equals("xx")) {
                    xx = true;
                } else {
                    return new RespError("ERR syntax error");
                }
            }
        }
        if (nx && xx) {
            return new RespError("ERR syntax error");
        }
        JsonNode newValue;
        try {
            newValue = MAPPER.readTree(value);
            if (newValue == null) {
                throw new IllegalArgumentException("empty json");
            }
        } catch (Exception e) {
            if (metrics != null) {
                metrics.recordJsonValidationError();
            }
            return new RespError("ERR invalid JSON");
        }
        JsonPath.Parsed parsed;
        try {
            parsed = JsonPath.parse(path);
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        byte[] existing = storage.get(args.get(0));
        if (existing != null && TypedValueCodec.typeOf(existing)
                != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        boolean exists = existing != null;
        if (nx && exists) {
            return RespNull.BULK_STRING;
        }
        if (xx && !exists) {
            return RespNull.BULK_STRING;
        }
        final String setPath = path;
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                if (current != null && TypedValueCodec.typeOf(current)
                        != ValueType.JSON) {
                    throw TypeSupport.wrongTypeException();
                }
                if (current == null && JsonPath.isJsonPath(setPath)
                        && !setPath.equals("$")) {
                    throw new JsonPath.JsonPathException(
                            "ERR new objects must be created "
                                    + "at the root");
                }
                JsonNode root;
                if (current == null) {
                    root = setPath.equals("$") ? newValue
                            : MAPPER.getNodeFactory().objectNode();
                } else {
                    root = readTree(MultiModelCodec.decodeJson(current));
                }
                JsonNode updated = JsonPath.set(root, parsed, newValue);
                byte[] encoded = MultiModelCodec.encodeJson(
                        serialize(updated));
                recordJsonWrite(encoded);
                return encoded;
            });
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
        return new RespSimpleString("OK");
    }

    private void recordJsonWrite(byte[] encoded) {
        if (metrics != null) {
            metrics.recordJsonWrite();
            metrics.recordMultiModelBytes(encoded.length);
        }
    }

    private RespValue jsonGet(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() < 1) {
            return RespError.wrongArity(name);
        }
        byte[] raw = storage.get(args.get(0));
        if (raw == null) {
            return RespNull.BULK_STRING;
        }
        if (TypedValueCodec.typeOf(raw) != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        if (args.size() == 1) {
            return new RespBulkString(TypedValueCodec.payload(raw));
        }
        try {
            JsonNode root = readTree(MultiModelCodec.decodeJson(raw));
            if (args.size() == 2) {
                String path = text(args.get(1));
                JsonPath.Parsed parsed = JsonPath.parse(path);
                List<JsonNode> matches = JsonPath.eval(root, parsed);
                if (!JsonPath.isJsonPath(path)) {
                    return matches.isEmpty() ? RespNull.BULK_STRING
                            : bulk(serialize(matches.get(0)));
                }
                ArrayNode array = MAPPER.createArrayNode();
                for (JsonNode match : matches) {
                    array.add(match);
                }
                return bulk(array.toString());
            }
            ObjectNode result = MAPPER.createObjectNode();
            for (int i = 1; i < args.size(); i++) {
                String path = text(args.get(i));
                JsonPath.Parsed parsed = JsonPath.parse(path);
                List<JsonNode> matches = JsonPath.eval(root, parsed);
                if (JsonPath.isJsonPath(path)) {
                    ArrayNode array = MAPPER.createArrayNode();
                    matches.forEach(array::add);
                    result.set(path, array);
                } else {
                    result.set(path, matches.isEmpty()
                            ? NullNode.instance : matches.get(0));
                }
            }
            return bulk(result.toString());
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
    }

    private RespValue jsonDel(List<byte[]> args,
                              StorageEngine storage) {
        if (args.size() != 1 && args.size() != 2) {
            return RespError.wrongArity(name);
        }
        String path = args.size() == 2 ? text(args.get(1)) : "$";
        byte[] raw = storage.get(args.get(0));
        if (raw == null) {
            return new RespInteger(0);
        }
        if (TypedValueCodec.typeOf(raw) != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        if (path.equals("$")) {
            storage.delete(args.get(0));
            return new RespInteger(1);
        }
        JsonPath.Parsed parsed;
        try {
            parsed = JsonPath.parse(path);
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        int[] removedHolder = {0};
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                if (current == null) {
                    return null;
                }
                JsonNode root = readTree(MultiModelCodec.decodeJson(
                        current));
                JsonPath.DeleteResult result =
                        JsonPath.delete(root, parsed);
                removedHolder[0] = result.removed() ? 1 : 0;
                return MultiModelCodec.encodeJson(
                        serialize(result.root()));
            });
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        return new RespInteger(removedHolder[0]);
    }

    private RespValue jsonType(List<byte[]> args,
                               StorageEngine storage) {
        return readSingleOrArray(args, storage,
                node -> new RespBulkString(bytes(typeName(node))));
    }

    private RespValue jsonArrLen(List<byte[]> args,
                                 StorageEngine storage) {
        return readSingleOrArray(args, storage, node -> {
            if (!node.isArray()) {
                throw new JsonPath.JsonPathException(
                        "ERR Existing key has wrong type");
            }
            return new RespInteger(node.size());
        });
    }

    private RespValue jsonObjLen(List<byte[]> args,
                                 StorageEngine storage) {
        return readSingleOrArray(args, storage, node -> {
            if (!node.isObject()) {
                throw new JsonPath.JsonPathException(
                        "ERR Existing key has wrong type");
            }
            return new RespInteger(node.size());
        });
    }

    private RespValue jsonStrLen(List<byte[]> args,
                                 StorageEngine storage) {
        return readSingleOrArray(args, storage, node -> {
            if (!node.isTextual()) {
                throw new JsonPath.JsonPathException(
                        "ERR Existing key has wrong type");
            }
            return new RespInteger(node.textValue().length());
        });
    }

    private RespValue jsonObjKeys(List<byte[]> args,
                                  StorageEngine storage) {
        return readSingleOrArray(args, storage, node -> {
            if (!node.isObject()) {
                throw new JsonPath.JsonPathException(
                        "ERR Existing key has wrong type");
            }
            List<RespValue> keys = new ArrayList<>();
            node.fieldNames().forEachRemaining(
                    field -> keys.add(bulk(field)));
            return new RespArray(keys);
        });
    }

    private RespValue readSingleOrArray(
            List<byte[]> args, StorageEngine storage,
            Function<JsonNode, RespValue> mapper) {
        if (args.size() != 1 && args.size() != 2) {
            return RespError.wrongArity(name);
        }
        String path = args.size() == 2 ? text(args.get(1)) : "$";
        byte[] raw = storage.get(args.get(0));
        if (raw == null) {
            return JsonPath.isJsonPath(path)
                    ? new RespArray(List.of())
                    : RespNull.BULK_STRING;
        }
        if (TypedValueCodec.typeOf(raw) != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        try {
            JsonNode root = readTree(MultiModelCodec.decodeJson(raw));
            List<JsonNode> matches = JsonPath.eval(root,
                    JsonPath.parse(path));
            if (!JsonPath.isJsonPath(path)) {
                return matches.isEmpty() ? RespNull.BULK_STRING
                        : mapper.apply(matches.get(0));
            }
            List<RespValue> values = new ArrayList<>();
            for (JsonNode match : matches) {
                values.add(mapper.apply(match));
            }
            return new RespArray(values);
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
    }

    private RespValue jsonArrAppend(List<byte[]> args,
                                    StorageEngine storage) {
        if (args.size() < 2) {
            return RespError.wrongArity(name);
        }
        String path;
        int valueStart;
        if (args.size() == 2) {
            path = "$";
            valueStart = 1;
        } else {
            path = text(args.get(1));
            valueStart = 2;
        }
        List<JsonNode> values = new ArrayList<>();
        for (int i = valueStart; i < args.size(); i++) {
            try {
                JsonNode node = MAPPER.readTree(text(args.get(i)));
                if (node == null) {
                    throw new IllegalArgumentException("empty json");
                }
                values.add(node);
            } catch (Exception e) {
                return new RespError("ERR invalid JSON");
            }
        }
        JsonPath.Parsed parsed;
        try {
            parsed = JsonPath.parse(path);
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        int[] lengthHolder = {0};
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                if (current == null) {
                    throw new JsonPath.JsonPathException(
                            "ERR key doesn't exist");
                }
                if (TypedValueCodec.typeOf(current)
                        != ValueType.JSON) {
                    throw TypeSupport.wrongTypeException();
                }
                JsonNode root = readTree(MultiModelCodec.decodeJson(
                        current));
                List<JsonNode> matches = JsonPath.eval(root, parsed);
                if (matches.size() != 1
                        || !matches.get(0).isArray()) {
                    throw new JsonPath.JsonPathException(
                            "ERR Existing key has wrong type");
                }
                ArrayNode array = (ArrayNode) matches.get(0);
                values.forEach(array::add);
                lengthHolder[0] = array.size();
                return MultiModelCodec.encodeJson(serialize(root));
            });
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        } catch (TypeSupport.WrongTypeException e) {
            return TypeSupport.wrongType();
        }
        return JsonPath.isJsonPath(path)
                ? new RespArray(List.of(
                new RespInteger(lengthHolder[0])))
                : new RespInteger(lengthHolder[0]);
    }

    private RespValue jsonNumIncrBy(List<byte[]> args,
                                    StorageEngine storage) {
        if (args.size() != 3) {
            return RespError.wrongArity(name);
        }
        double increment;
        try {
            increment = Double.parseDouble(text(args.get(2)));
        } catch (NumberFormatException e) {
            return new RespError("ERR invalid number");
        }
        String path = text(args.get(1));
        JsonPath.Parsed parsed;
        try {
            parsed = JsonPath.parse(path);
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        byte[] raw = storage.get(args.get(0));
        if (raw == null) {
            return RespNull.BULK_STRING;
        }
        if (TypedValueCodec.typeOf(raw) != ValueType.JSON) {
            return TypeSupport.wrongType();
        }
        String[] resultHolder = {null};
        try {
            TypeSupport.update(storage, args.get(0), current -> {
                JsonNode root = readTree(MultiModelCodec.decodeJson(
                        current));
                List<JsonNode> matches = JsonPath.eval(root, parsed);
                if (matches.size() != 1) {
                    throw new JsonPath.JsonPathException(
                            "ERR path does not exist");
                }
                JsonNode target = matches.get(0);
                if (!target.isNumber()) {
                    throw new JsonPath.JsonPathException(
                            "ERR Existing key has wrong type");
                }
                double updated = target.doubleValue() + increment;
                double displayed =
                        Math.abs(updated - Math.rint(updated)) < 1e-9
                                ? Math.rint(updated) : updated;
                JsonNode number = displayed == Math.rint(displayed)
                        ? MAPPER.getNodeFactory().numberNode(
                        (long) displayed)
                        : MAPPER.getNodeFactory().numberNode(displayed);
                JsonNode rootAfter = JsonPath.set(root, parsed, number);
                resultHolder[0] = TypeSupport.formatScore(displayed);
                return MultiModelCodec.encodeJson(
                        serialize(rootAfter));
            });
        } catch (JsonPath.JsonPathException e) {
            return new RespError(e.getMessage());
        }
        return resultHolder[0] == null
                ? RespNull.BULK_STRING
                : bulk(resultHolder[0]);
    }

    private static JsonNode readTree(String json) {
        try {
            JsonNode node = MAPPER.readTree(json);
            if (node == null) {
                throw new IllegalArgumentException("empty json");
            }
            return node;
        } catch (Exception e) {
            throw new IllegalStateException(
                    "stored json is invalid", e);
        }
    }

    private static String serialize(JsonNode node) {
        try {
            return MAPPER.writeValueAsString(node);
        } catch (Exception e) {
            throw new IllegalStateException(
                    "json serialize failed", e);
        }
    }

    private static String typeName(JsonNode node) {
        if (node.isObject()) {
            return "object";
        }
        if (node.isArray()) {
            return "array";
        }
        if (node.isTextual()) {
            return "string";
        }
        if (node.isNumber()) {
            return "number";
        }
        if (node.isBoolean()) {
            return "boolean";
        }
        if (node.isNull()) {
            return "null";
        }
        return "unknown";
    }

    private static RespBulkString bulk(String value) {
        return new RespBulkString(bytes(value));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
