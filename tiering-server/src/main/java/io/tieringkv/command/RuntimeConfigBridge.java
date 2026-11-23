package io.tieringkv.command;

import io.tieringkv.storage.wal.WALConfig;

import java.util.Collections;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;
import java.util.function.IntConsumer;
import java.util.function.IntSupplier;
import java.util.function.LongConsumer;
import java.util.function.LongSupplier;
import java.util.function.Supplier;

/**
 * 运行期参数读写桥接：将 CONFIG GET/SET 真实映射到内存预算、WAL 刷盘、连接限制、空闲超时与口令鉴权。
 */
public final class RuntimeConfigBridge {

    private final LongSupplier maxMemoryGetter;
    private final LongConsumer maxMemorySetter;
    private final Supplier<WALConfig.FsyncPolicy> fsyncGetter;
    private final Consumer<WALConfig.FsyncPolicy> fsyncSetter;
    private final IntSupplier maxClientsGetter;
    private final IntConsumer maxClientsSetter;
    private final IntSupplier timeoutGetter;
    private final IntConsumer timeoutSetter;
    private final boolean requirePassEnabled;
    private final BooleanSupplier passwordConfigured;
    private final Consumer<String> passwordSetter;
    private volatile String saveSchedule = "3600 1";

    private RuntimeConfigBridge(Builder builder) {
        this.maxMemoryGetter = builder.maxMemoryGetter;
        this.maxMemorySetter = builder.maxMemorySetter;
        this.fsyncGetter = builder.fsyncGetter;
        this.fsyncSetter = builder.fsyncSetter;
        this.maxClientsGetter = builder.maxClientsGetter;
        this.maxClientsSetter = builder.maxClientsSetter;
        this.timeoutGetter = builder.timeoutGetter;
        this.timeoutSetter = builder.timeoutSetter;
        this.requirePassEnabled = builder.requirePassEnabled;
        this.passwordConfigured = builder.passwordConfigured;
        this.passwordSetter = builder.passwordSetter;
    }

    public static RuntimeConfigBridge standaloneDefaults() {
        final long[] mem = {1073741824L};
        final WALConfig.FsyncPolicy[] fsync = {WALConfig.FsyncPolicy.EVERY_SEC};
        final int[] maxClients = {10000};
        final int[] timeout = {0};
        return builder()
                .maxMemory(() -> mem[0], v -> mem[0] = v)
                .appendFsync(() -> fsync[0], p -> fsync[0] = p)
                .maxClients(() -> maxClients[0], v -> maxClients[0] = v)
                .timeout(() -> timeout[0], v -> timeout[0] = v)
                .build();
    }

    public static Builder builder() {
        return new Builder();
    }

    public Map<String, String> snapshot() {
        Map<String, String> map = new TreeMap<>();
        map.put("appendfsync", fsyncGetter.get().name().toLowerCase(Locale.ROOT).replace("_", ""));
        map.put("maxclients", Integer.toString(maxClientsGetter.getAsInt()));
        map.put("maxmemory", Long.toString(maxMemoryGetter.getAsLong()));
        if (requirePassEnabled) {
            map.put("requirepass", passwordConfigured.getAsBoolean() ? "********" : "");
        }
        map.put("save", saveSchedule);
        map.put("timeout", Integer.toString(timeoutGetter.getAsInt()));
        return Collections.unmodifiableMap(map);
    }

    public boolean supports(String param) {
        return switch (param.toLowerCase(Locale.ROOT)) {
            case "maxmemory", "appendfsync", "maxclients", "timeout", "save" -> true;
            case "requirepass" -> requirePassEnabled;
            default -> false;
        };
    }

    public void set(String param, String value) {
        String key = param.toLowerCase(Locale.ROOT);
        switch (key) {
            case "maxmemory" -> {
                long bytes = parsePositiveLong(value, "maxmemory");
                maxMemorySetter.accept(bytes);
            }
            case "appendfsync" -> fsyncSetter.accept(parseFsyncPolicy(value));
            case "maxclients" -> {
                int clients = parseIntRange(value, 1, 1_000_000, "maxclients");
                maxClientsSetter.accept(clients);
            }
            case "timeout" -> {
                int seconds = parseIntRange(value, 0, Integer.MAX_VALUE, "timeout");
                timeoutSetter.accept(seconds);
            }
            case "requirepass" -> passwordSetter.accept(value);
            case "save" -> saveSchedule = value;
            default -> throw new IllegalArgumentException("Unsupported CONFIG parameter: " + key);
        }
    }

    private static long parsePositiveLong(String raw, String name) {
        try {
            long v = Long.parseLong(raw.trim());
            if (v <= 0) {
                throw new IllegalArgumentException("Invalid argument '" + raw + "' for CONFIG SET '" + name + "'");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid argument '" + raw + "' for CONFIG SET '" + name + "'");
        }
    }

    private static int parseIntRange(String raw, int min, int max, String name) {
        try {
            int v = Integer.parseInt(raw.trim());
            if (v < min || v > max) {
                throw new IllegalArgumentException("Invalid argument '" + raw + "' for CONFIG SET '" + name + "'");
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("Invalid argument '" + raw + "' for CONFIG SET '" + name + "'");
        }
    }

    private static WALConfig.FsyncPolicy parseFsyncPolicy(String raw) {
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "always" -> WALConfig.FsyncPolicy.ALWAYS;
            case "everysec", "every_sec" -> WALConfig.FsyncPolicy.EVERY_SEC;
            case "no" -> WALConfig.FsyncPolicy.NO;
            default -> throw new IllegalArgumentException(
                    "Invalid argument '" + raw + "' for CONFIG SET 'appendfsync'");
        };
    }

    public static final class Builder {
        private LongSupplier maxMemoryGetter = () -> 1073741824L;
        private LongConsumer maxMemorySetter = v -> {
        };
        private Supplier<WALConfig.FsyncPolicy> fsyncGetter = () -> WALConfig.FsyncPolicy.EVERY_SEC;
        private Consumer<WALConfig.FsyncPolicy> fsyncSetter = p -> {
        };
        private IntSupplier maxClientsGetter = () -> 10000;
        private IntConsumer maxClientsSetter = v -> {
        };
        private IntSupplier timeoutGetter = () -> 0;
        private IntConsumer timeoutSetter = v -> {
        };
        private boolean requirePassEnabled;
        private BooleanSupplier passwordConfigured = () -> false;
        private Consumer<String> passwordSetter = p -> {
        };

        public Builder maxMemory(LongSupplier getter, LongConsumer setter) {
            this.maxMemoryGetter = getter;
            this.maxMemorySetter = setter;
            return this;
        }

        public Builder appendFsync(Supplier<WALConfig.FsyncPolicy> getter, Consumer<WALConfig.FsyncPolicy> setter) {
            this.fsyncGetter = getter;
            this.fsyncSetter = setter;
            return this;
        }

        public Builder maxClients(IntSupplier getter, IntConsumer setter) {
            this.maxClientsGetter = getter;
            this.maxClientsSetter = setter;
            return this;
        }

        public Builder timeout(IntSupplier getter, IntConsumer setter) {
            this.timeoutGetter = getter;
            this.timeoutSetter = setter;
            return this;
        }

        public Builder requirePass(BooleanSupplier configured, Consumer<String> setter) {
            this.requirePassEnabled = true;
            this.passwordConfigured = configured;
            this.passwordSetter = setter;
            return this;
        }

        public RuntimeConfigBridge build() {
            return new RuntimeConfigBridge(this);
        }
    }
}
