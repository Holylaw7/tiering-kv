package io.tieringkv.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Redis JSON 路径子集（ADR-0336）：`$`、`.field`、`['field']`、
 * `[n]`（含负索引）、`.*`/`[*]` 通配、`..field`/`..*` 递归下降。
 *
 * <p>读路径支持全部子集；变更路径（{@link #set}/{@link #delete}）
 * 仅支持根与简单字段/索引链（通配/递归变更暂不支持，文档登记）。
 */
public final class JsonPath {

    public enum Kind {
        ROOT,
        FIELD,
        INDEX,
        WILDCARD,
        RECURSIVE_FIELD,
        RECURSIVE_WILDCARD
    }

    /** 路径 Token：FIELD 用 name，INDEX 用 index。 */
    public record Token(Kind kind, String name, int index) {
        private static Token root() {
            return new Token(Kind.ROOT, null, 0);
        }

        private static Token field(String name) {
            return new Token(Kind.FIELD, name, 0);
        }

        private static Token index(int index) {
            return new Token(Kind.INDEX, null, index);
        }

        private static Token wildcard() {
            return new Token(Kind.WILDCARD, null, 0);
        }

        private static Token recursiveField(String name) {
            return new Token(Kind.RECURSIVE_FIELD, name, 0);
        }

        private static Token recursiveWildcard() {
            return new Token(Kind.RECURSIVE_WILDCARD, null, 0);
        }
    }

    /** 解析结果：原始路径 + 是否 JSONPath（`$` 前缀）+ Token 链。 */
    public record Parsed(String raw, boolean jsonPath,
                         List<Token> tokens) {
        public Parsed {
            tokens = List.copyOf(tokens);
        }
    }

    /** 路径语法/语义错误（含 RedisJSON 风格错误文本）。 */
    public static final class JsonPathException
            extends RuntimeException {
        public JsonPathException(String message) {
            super(message);
        }
    }

    private JsonPath() {
    }

    public static boolean isJsonPath(String path) {
        return path != null && path.startsWith("$");
    }

    public static Parsed parse(String path) {
        if (path == null || path.isEmpty()) {
            throw new JsonPathException("ERR invalid path");
        }
        boolean jsonPath = path.charAt(0) == '$';
        int i = jsonPath ? 1 : 0;
        List<Token> tokens = new ArrayList<>();
        if (jsonPath) {
            tokens.add(Token.root());
        }
        while (i < path.length()) {
            char c = path.charAt(i);
            if (c == '.') {
                i++;
                boolean recursive = false;
                if (i < path.length() && path.charAt(i) == '.') {
                    recursive = true;
                    i++;
                }
                if (i >= path.length()) {
                    break; // 尾随点容忍
                }
                if (path.charAt(i) == '*') {
                    tokens.add(recursive ? Token.recursiveWildcard()
                            : Token.wildcard());
                    i++;
                    continue;
                }
                int start = i;
                while (i < path.length() && path.charAt(i) != '.'
                        && path.charAt(i) != '[') {
                    i++;
                }
                if (i == start) {
                    throw new JsonPathException("ERR invalid path");
                }
                String name = path.substring(start, i);
                tokens.add(recursive ? Token.recursiveField(name)
                        : Token.field(name));
            } else if (c == '[') {
                i++;
                if (i < path.length() && path.charAt(i) == '*') {
                    tokens.add(Token.wildcard());
                    i++;
                    expectClose(path, i);
                    i++;
                } else if (i < path.length()
                        && (path.charAt(i) == '\''
                        || path.charAt(i) == '"')) {
                    char quote = path.charAt(i);
                    i++;
                    int start = i;
                    while (i < path.length()
                            && path.charAt(i) != quote) {
                        i++;
                    }
                    if (i >= path.length()) {
                        throw new JsonPathException("ERR invalid path");
                    }
                    tokens.add(Token.field(path.substring(start, i)));
                    i++;
                    expectClose(path, i);
                    i++;
                } else {
                    int start = i;
                    if (i < path.length() && path.charAt(i) == '-') {
                        i++;
                    }
                    while (i < path.length()
                            && Character.isDigit(path.charAt(i))) {
                        i++;
                    }
                    if (i == start) {
                        throw new JsonPathException("ERR invalid path");
                    }
                    tokens.add(Token.index(Integer.parseInt(
                            path.substring(start, i))));
                    expectClose(path, i);
                    i++;
                }
            } else {
                throw new JsonPathException("ERR invalid path");
            }
        }
        if (tokens.isEmpty()) {
            throw new JsonPathException("ERR invalid path");
        }
        return new Parsed(path, jsonPath, tokens);
    }

    private static void expectClose(String path, int index) {
        if (index >= path.length() || path.charAt(index) != ']') {
            throw new JsonPathException("ERR invalid path");
        }
    }

    /** 读路径求值：返回全部匹配节点（文档序，允许重复）。 */
    public static List<JsonNode> eval(JsonNode root, Parsed parsed) {
        List<JsonNode> result = new ArrayList<>();
        evalRec(root, parsed.tokens(), 0, result);
        return result;
    }

    private static void evalRec(JsonNode node, List<Token> tokens,
                                int pos, List<JsonNode> out) {
        if (pos >= tokens.size()) {
            out.add(node);
            return;
        }
        Token token = tokens.get(pos);
        switch (token.kind()) {
            case ROOT -> evalRec(node, tokens, pos + 1, out);
            case FIELD -> {
                if (node.isObject() && node.has(token.name())) {
                    evalRec(node.get(token.name()), tokens,
                            pos + 1, out);
                }
            }
            case INDEX -> {
                if (node.isArray()) {
                    int index = normalizeIndex(token.index(),
                            node.size());
                    if (index >= 0 && index < node.size()) {
                        evalRec(node.get(index), tokens,
                                pos + 1, out);
                    }
                }
            }
            case WILDCARD -> {
                for (JsonNode child : children(node)) {
                    evalRec(child, tokens, pos + 1, out);
                }
            }
            case RECURSIVE_FIELD -> {
                if (node.isObject() && node.has(token.name())) {
                    evalRec(node.get(token.name()), tokens,
                            pos + 1, out);
                }
                for (JsonNode child : children(node)) {
                    evalRec(child, tokens, pos, out);
                }
            }
            case RECURSIVE_WILDCARD -> {
                evalRec(node, tokens, pos + 1, out);
                for (JsonNode child : children(node)) {
                    evalRec(child, tokens, pos, out);
                }
            }
            default -> throw new JsonPathException(
                    "ERR unsupported path token");
        }
    }

    /** 变更路径：SET 覆盖，缺失对象字段按需创建。 */
    public static JsonNode set(JsonNode root, Parsed parsed,
                               JsonNode value) {
        if (parsed.tokens().size() == 1
                && parsed.tokens().get(0).kind() == Kind.ROOT) {
            return value;
        }
        requireSimple(parsed, "JSON.SET");
        List<Token> tokens = parsed.tokens();
        int start = parsed.jsonPath() ? 1 : 0;
        JsonNode current = root;
        for (int i = start; i < tokens.size() - 1; i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.FIELD) {
                if (!(current instanceof ObjectNode object)) {
                    throw new JsonPathException(
                            "ERR existing key has wrong type");
                }
                JsonNode child = object.get(token.name());
                if (child == null) {
                    Token next = tokens.get(i + 1);
                    child = next.kind() == Kind.INDEX
                            ? JsonNodeFactory.instance.arrayNode()
                            : JsonNodeFactory.instance.objectNode();
                    object.set(token.name(), child);
                }
                current = child;
            } else if (token.kind() == Kind.INDEX) {
                if (!(current instanceof ArrayNode array)) {
                    throw new JsonPathException(
                            "ERR existing key has wrong type");
                }
                int index = normalizeIndex(token.index(),
                        array.size());
                if (index < 0 || index >= array.size()) {
                    throw new JsonPathException(
                            "ERR index out of bounds");
                }
                current = array.get(index);
            } else {
                throw new JsonPathException(
                        "ERR unsupported path token for mutation");
            }
        }
        Token last = tokens.get(tokens.size() - 1);
        if (last.kind() == Kind.FIELD) {
            if (!(current instanceof ObjectNode object)) {
                throw new JsonPathException(
                        "ERR existing key has wrong type");
            }
            object.set(last.name(), value);
        } else if (last.kind() == Kind.INDEX) {
            if (!(current instanceof ArrayNode array)) {
                throw new JsonPathException(
                        "ERR existing key has wrong type");
            }
            int index = normalizeIndex(last.index(), array.size());
            if (index < 0 || index >= array.size()) {
                throw new JsonPathException("ERR index out of bounds");
            }
            array.set(index, value);
        } else {
            throw new JsonPathException(
                    "ERR unsupported path token for mutation");
        }
        return root;
    }

    /** 变更路径：DEL 单匹配删除，返回是否删除。 */
    public static DeleteResult delete(JsonNode root, Parsed parsed) {
        requireSimple(parsed, "JSON.DEL");
        List<Token> tokens = parsed.tokens();
        int start = parsed.jsonPath() ? 1 : 0;
        JsonNode current = root;
        for (int i = start; i < tokens.size() - 1; i++) {
            Token token = tokens.get(i);
            if (token.kind() == Kind.FIELD) {
                if (!(current instanceof ObjectNode object)
                        || !object.has(token.name())) {
                    return new DeleteResult(root, false);
                }
                current = object.get(token.name());
            } else if (token.kind() == Kind.INDEX) {
                if (!(current instanceof ArrayNode array)) {
                    return new DeleteResult(root, false);
                }
                int index = normalizeIndex(token.index(),
                        array.size());
                if (index < 0 || index >= array.size()) {
                    return new DeleteResult(root, false);
                }
                current = array.get(index);
            } else {
                throw new JsonPathException(
                        "ERR unsupported path token for mutation");
            }
        }
        Token last = tokens.get(tokens.size() - 1);
        if (last.kind() == Kind.FIELD) {
            if (!(current instanceof ObjectNode object)
                    || !object.has(last.name())) {
                return new DeleteResult(root, false);
            }
            object.remove(last.name());
            return new DeleteResult(root, true);
        }
        if (last.kind() == Kind.INDEX) {
            if (!(current instanceof ArrayNode array)) {
                return new DeleteResult(root, false);
            }
            int index = normalizeIndex(last.index(), array.size());
            if (index < 0 || index >= array.size()) {
                return new DeleteResult(root, false);
            }
            array.remove(index);
            return new DeleteResult(root, true);
        }
        throw new JsonPathException(
                "ERR unsupported path token for mutation");
    }

    public record DeleteResult(JsonNode root, boolean removed) {
    }

    private static void requireSimple(Parsed parsed, String command) {
        for (Token token : parsed.tokens()) {
            if (token.kind() == Kind.WILDCARD
                    || token.kind() == Kind.RECURSIVE_FIELD
                    || token.kind() == Kind.RECURSIVE_WILDCARD) {
                throw new JsonPathException("ERR path wildcards are "
                        + "not supported for " + command);
            }
        }
    }

    private static int normalizeIndex(int index, int size) {
        return index < 0 ? size + index : index;
    }

    private static List<JsonNode> children(JsonNode node) {
        List<JsonNode> children = new ArrayList<>();
        if (node.isObject()) {
            node.elements().forEachRemaining(children::add);
        } else if (node.isArray()) {
            node.elements().forEachRemaining(children::add);
        }
        return children;
    }
}
