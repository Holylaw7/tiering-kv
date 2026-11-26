package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespValue;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * 将解码后的 RESP 数组转换为命令；Redis 请求约定为 bulk string 数组。
 *
 * <p>B1 热路径优化：高频命令名直接按 ASCII 字节忽略大小写匹配常量字符串，
 * 避免每请求分配两次临时 String（UTF-8 解码 + {@code toLowerCase}）。
 */
public final class RespRequestParser {

    private RespRequestParser() {
    }

    public static RespCommand parse(RespValue value) {
        if (!(value instanceof RespArray array)) {
            throw new RespProtocolException("expected array");
        }
        List<RespValue> values = array.values();
        int size = values.size();
        if (size == 0) {
            throw new RespProtocolException("empty command");
        }
        RespValue head = values.get(0);
        if (!(head instanceof RespBulkString name)) {
            throw new RespProtocolException("command name must be a bulk string");
        }
        List<byte[]> args;
        if (size == 1) {
            args = List.of();
        } else {
            args = new ArrayList<>(size - 1);
            for (int i = 1; i < size; i++) {
                RespValue arg = values.get(i);
                if (!(arg instanceof RespBulkString bulk)) {
                    throw new RespProtocolException("command arguments must be bulk strings");
                }
                args.add(bulk.bytes());
            }
        }
        String nameLower = internHotCommand(name.bytes());
        return new RespCommand(nameLower, args);
    }

    /** 零分配匹配高频命令名（ASCII 大小写不敏感）；未命中回退通用 UTF-8 转小写。 */
    private static String internHotCommand(byte[] b) {
        return switch (b.length) {
            case 3 -> {
                if (eq3(b, 'g', 'e', 't')) yield "get";
                if (eq3(b, 's', 'e', 't')) yield "set";
                if (eq3(b, 'd', 'e', 'l')) yield "del";
                if (eq3(b, 't', 't', 'l')) yield "ttl";
                yield fallbackLower(b);
            }
            case 4 -> {
                if (eq4(b, 'p', 'i', 'n', 'g')) yield "ping";
                if (eq4(b, 'i', 'n', 'c', 'r')) yield "incr";
                if (eq4(b, 'd', 'e', 'c', 'r')) yield "decr";
                if (eq4(b, 'h', 'g', 'e', 't')) yield "hget";
                if (eq4(b, 'h', 's', 'e', 't')) yield "hset";
                if (eq4(b, 'h', 'd', 'e', 'l')) yield "hdel";
                if (eq4(b, 'm', 'g', 'e', 't')) yield "mget";
                if (eq4(b, 'm', 's', 'e', 't')) yield "mset";
                if (eq4(b, 's', 'a', 'd', 'd')) yield "sadd";
                if (eq4(b, 's', 'r', 'e', 'm')) yield "srem";
                if (eq4(b, 'z', 'a', 'd', 'd')) yield "zadd";
                if (eq4(b, 'l', 'p', 'o', 'p')) yield "lpop";
                if (eq4(b, 'r', 'p', 'o', 'p')) yield "rpop";
                if (eq4(b, 'i', 'n', 'f', 'o')) yield "info";
                if (eq4(b, 'a', 'u', 't', 'h')) yield "auth";
                if (eq4(b, 'e', 'x', 'e', 'c')) yield "exec";
                if (eq4(b, 's', 'c', 'a', 'n')) yield "scan";
                if (eq4(b, 'k', 'e', 'y', 's')) yield "keys";
                if (eq4(b, 'e', 'c', 'h', 'o')) yield "echo";
                yield fallbackLower(b);
            }
            case 5 -> {
                if (eqAscii(b, "lpush")) yield "lpush";
                if (eqAscii(b, "rpush")) yield "rpush";
                if (eqAscii(b, "setex")) yield "setex";
                if (eqAscii(b, "setnx")) yield "setnx";
                if (eqAscii(b, "hello")) yield "hello";
                if (eqAscii(b, "multi")) yield "multi";
                yield fallbackLower(b);
            }
            case 6 -> {
                if (eqAscii(b, "exists")) yield "exists";
                if (eqAscii(b, "expire")) yield "expire";
                if (eqAscii(b, "zrange")) yield "zrange";
                if (eqAscii(b, "dbsize")) yield "dbsize";
                if (eqAscii(b, "append")) yield "append";
                if (eqAscii(b, "getset")) yield "getset";
                if (eqAscii(b, "incrby")) yield "incrby";
                yield fallbackLower(b);
            }
            default -> fallbackLower(b);
        };
    }

    private static boolean eq3(byte[] b, char c0, char c1, char c2) {
        return lowerAscii(b[0]) == c0 && lowerAscii(b[1]) == c1 && lowerAscii(b[2]) == c2;
    }

    private static boolean eq4(byte[] b, char c0, char c1, char c2, char c3) {
        return lowerAscii(b[0]) == c0 && lowerAscii(b[1]) == c1
                && lowerAscii(b[2]) == c2 && lowerAscii(b[3]) == c3;
    }

    private static boolean eqAscii(byte[] b, String targetLower) {
        for (int i = 0; i < b.length; i++) {
            if (lowerAscii(b[i]) != targetLower.charAt(i)) {
                return false;
            }
        }
        return true;
    }

    private static int lowerAscii(byte raw) {
        int c = raw & 0xff;
        return (c >= 'A' && c <= 'Z') ? (c + 32) : c;
    }

    private static String fallbackLower(byte[] bytes) {
        return new String(bytes, StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
    }
}
