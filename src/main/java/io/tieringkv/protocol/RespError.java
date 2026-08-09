package io.tieringkv.protocol;

import java.util.Objects;

/** RESP2 错误响应，如 {@code -ERR msg\r\n}。 */
public record RespError(String message) implements RespValue {

    public RespError {
        Objects.requireNonNull(message, "message");
    }

    public static RespError wrongArity(String commandName) {
        return new RespError("ERR wrong number of arguments for '" + commandName + "' command");
    }

    public static RespError unknownCommand(String commandName) {
        return new RespError("ERR unknown command '" + commandName + "'");
    }

    public static RespError protocol(String message) {
        return new RespError("ERR Protocol error: " + message);
    }
}
