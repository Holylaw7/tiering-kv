package io.tieringkv.command;

import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** 命令测试运行器：任意存储上执行注册表命令。 */
public final class TestCommandRunner {

    private final CommandEngine engine;

    public TestCommandRunner(StorageEngine storage) {
        this(storage, CommandRegistry.createDefault());
    }

    public TestCommandRunner(StorageEngine storage,
                             CommandRegistry registry) {
        this.engine = new CommandEngine(registry, storage);
    }

    public RespValue exec(String name, Object... args) {
        return engine.execute(new RespCommand(name, bytes(args)));
    }

    public static List<byte[]> bytes(Object... args) {
        List<byte[]> result = new ArrayList<>(args.length);
        for (Object arg : args) {
            if (arg instanceof byte[] bytes) {
                result.add(bytes);
            } else if (arg instanceof String text) {
                result.add(text.getBytes(StandardCharsets.UTF_8));
            } else if (arg instanceof Long value) {
                result.add(Long.toString(value)
                        .getBytes(StandardCharsets.UTF_8));
            } else if (arg instanceof Integer value) {
                result.add(Integer.toString(value)
                        .getBytes(StandardCharsets.UTF_8));
            } else if (arg == null) {
                throw new IllegalArgumentException("null arg");
            } else {
                throw new IllegalArgumentException(
                        "unsupported arg type " + arg.getClass());
            }
        }
        return result;
    }
}
