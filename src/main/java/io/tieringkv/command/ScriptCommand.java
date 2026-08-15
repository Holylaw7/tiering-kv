package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SCRIPT 命令族（ADR-0340）：LOAD（SHA1 注册表）/EXISTS/FLUSH。
 *
 * <p>EVAL/EVALSHA 注册但返回显式"scripting engine not available"
 * （无 Lua 运行时，诚实登记，避免伪造执行）。
 */
public final class ScriptCommand implements Command {

    public static final String NOT_AVAILABLE =
            "ERR scripting engine not available in this build "
                    + "(no Lua runtime)";

    private final String name;
    private final Map<String, byte[]> scripts =
            new ConcurrentHashMap<>();

    public ScriptCommand(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (name.equals("eval") || name.equals("evalsha")) {
            return new RespError(NOT_AVAILABLE);
        }
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String subcommand = text(args.get(0))
                .toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "load" -> load(args);
            case "exists" -> exists(args);
            case "flush" -> flush(args);
            default -> new RespError(
                    "ERR Unknown SCRIPT subcommand");
        };
    }

    private RespValue load(List<byte[]> args) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        String sha = sha1(args.get(1));
        scripts.put(sha, args.get(1));
        return new RespBulkString(sha.getBytes(
                StandardCharsets.UTF_8));
    }

    private RespValue exists(List<byte[]> args) {
        if (args.size() < 2) {
            return RespError.wrongArity(name());
        }
        List<RespValue> flags = new ArrayList<>();
        for (int i = 1; i < args.size(); i++) {
            flags.add(new RespInteger(
                    scripts.containsKey(text(args.get(i))) ? 1 : 0));
        }
        return new RespArray(flags);
    }

    private RespValue flush(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        scripts.clear();
        return new RespSimpleString("OK");
    }

    private static String sha1(byte[] script) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-1");
            byte[] hash = digest.digest(script);
            StringBuilder hex = new StringBuilder(hash.length * 2);
            for (byte b : hash) {
                hex.append(String.format(Locale.ROOT, "%02x", b));
            }
            return hex.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-1 unavailable", e);
        }
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
