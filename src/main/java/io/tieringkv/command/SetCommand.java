package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/** SET key value [EX seconds | PX milliseconds]：写入并返回 OK（Phase 2 支持 TTL）。 */
public final class SetCommand implements Command {

    @Override
    public String name() {
        return "set";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.size() == 2) {
            storage.put(args.get(0), args.get(1));
            return new RespSimpleString("OK");
        }
        if (args.size() == 4) {
            String option = new String(args.get(2), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
            try {
                long ttlMillis;
                if ("ex".equals(option)) {
                    ttlMillis = parseSecondsToMillis(args.get(3));
                } else if ("px".equals(option)) {
                    ttlMillis = parseMillis(args.get(3));
                } else {
                    return new RespError("ERR syntax error");
                }
                storage.put(args.get(0), args.get(1), ttlMillis);
                return new RespSimpleString("OK");
            } catch (TtlParseException e) {
                return new RespError("ERR value is not an integer or out of range");
            }
        }
        return RespError.wrongArity(name());
    }

    private static long parseSecondsToMillis(byte[] bytes) {
        try {
            return Math.multiplyExact(Long.parseLong(new String(bytes, StandardCharsets.UTF_8).trim()), 1000);
        } catch (NumberFormatException | ArithmeticException e) {
            throw new TtlParseException();
        }
    }

    private static long parseMillis(byte[] bytes) {
        try {
            return Long.parseLong(new String(bytes, StandardCharsets.UTF_8).trim());
        } catch (NumberFormatException e) {
            throw new TtlParseException();
        }
    }

    private static final class TtlParseException extends RuntimeException {
    }
}
