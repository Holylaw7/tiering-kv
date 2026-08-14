package io.tieringkv.storage.types;

/**
 * JSON 最小结构校验（ADR-0320）：括号配对、字符串引号配对、尾随内容
 * 拒绝、顶层字面量允许。不做完整语法/数字格式校验（由解析层负责）。
 */
public final class JsonValidator {

    private JsonValidator() {
    }

    public static void validate(String json) {
        if (json == null) {
            throw new IllegalArgumentException("json required");
        }
        int index = 0;
        int length = json.length();
        while (index < length && isWhitespace(json.charAt(index))) {
            index++;
        }
        if (index >= length) {
            throw new IllegalArgumentException("empty json");
        }
        int end = parseValue(json, index);
        while (end < length && isWhitespace(json.charAt(end))) {
            end++;
        }
        if (end != length) {
            throw new IllegalArgumentException(
                    "trailing content after json value");
        }
    }

    private static int parseValue(String json, int index) {
        char c = json.charAt(index);
        if (c == '{') {
            return parseContainer(json, index, '{', '}');
        }
        if (c == '[') {
            return parseContainer(json, index, '[', ']');
        }
        if (c == '"') {
            return parseString(json, index);
        }
        return parseLiteral(json, index);
    }

    private static int parseContainer(String json, int index,
                                      char open, char close) {
        int cursor = index + 1;
        while (cursor < json.length()) {
            char c = json.charAt(cursor);
            if (c == '"') {
                cursor = parseString(json, cursor);
            } else if (c == close) {
                return cursor + 1;
            } else if (c == '{' || c == '[') {
                cursor = parseValue(json, cursor);
            } else {
                cursor++;
            }
        }
        throw new IllegalArgumentException(
                "unclosed container '" + open + "'");
    }

    private static int parseString(String json, int index) {
        int cursor = index + 1;
        while (cursor < json.length()) {
            char c = json.charAt(cursor);
            if (c == '\\') {
                cursor += 2;
                continue;
            }
            if (c == '"') {
                return cursor + 1;
            }
            cursor++;
        }
        throw new IllegalArgumentException("unclosed string");
    }

    private static int parseLiteral(String json, int index) {
        int cursor = index;
        while (cursor < json.length()
                && !isWhitespace(json.charAt(cursor))
                && ",]}".indexOf(json.charAt(cursor)) < 0) {
            cursor++;
        }
        if (cursor == index) {
            throw new IllegalArgumentException("invalid json value");
        }
        return cursor;
    }

    private static boolean isWhitespace(char c) {
        return c == ' ' || c == '\t' || c == '\n' || c == '\r';
    }
}
