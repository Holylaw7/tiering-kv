package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** 将解码后的 RESP 数组转换为命令；Redis 请求约定为 bulk string 数组。 */
public final class RespRequestParser {

    private RespRequestParser() {
    }

    public static RespCommand parse(RespValue value) {
        if (!(value instanceof RespArray array)) {
            throw new RespProtocolException("expected array");
        }
        if (array.values().isEmpty()) {
            throw new RespProtocolException("empty command");
        }
        RespValue head = array.values().get(0);
        if (!(head instanceof RespBulkString name)) {
            throw new RespProtocolException("command name must be a bulk string");
        }
        List<byte[]> args = new ArrayList<>(array.values().size() - 1);
        for (int i = 1; i < array.values().size(); i++) {
            RespValue arg = array.values().get(i);
            if (!(arg instanceof RespBulkString bulk)) {
                throw new RespProtocolException("command arguments must be bulk strings");
            }
            args.add(bulk.bytes());
        }
        String nameLower = new String(name.bytes(), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
        return new RespCommand(nameLower, args);
    }
}
