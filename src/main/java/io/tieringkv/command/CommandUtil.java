package io.tieringkv.command;

import java.nio.charset.StandardCharsets;

/** 命令层公共工具（Phase 51）：整数解析与字节文本转换。 */
final class CommandUtil {

    static final String NOT_INTEGER =
            "ERR value is not an integer or out of range";

    private CommandUtil() {
    }

    static long parseLong(byte[] bytes) {
        return Long.parseLong(new String(bytes,
                StandardCharsets.UTF_8).trim());
    }

    static byte[] bytes(long value) {
        return Long.toString(value)
                .getBytes(StandardCharsets.UTF_8);
    }

    static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    static String text(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8);
    }
}
