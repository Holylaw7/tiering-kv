package io.tieringkv.runtime;

import java.util.HashMap;
import java.util.Map;

/**
 * 事务运行时入口（ADR-0093）：按 --role 启动独立 JVM 角色。
 * 全链路 TCP：gateway → coordinator → participant → metadata。
 */
public final class TxnRuntimeMain {

    private TxnRuntimeMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String role = require(options, "role");
        switch (role) {
            case "participant" -> ParticipantRuntime.start(options);
            case "metadata" -> MetadataRuntime.start(options);
            case "coordinator" -> CoordinatorRuntime.start(options);
            case "gateway" -> GatewayRuntime.start(options);
            default -> throw new IllegalArgumentException(
                    "unknown role " + role);
        }
    }

    public static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    public static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    public static int port(Map<String, String> options, String key,
                           int fallback) {
        return Integer.parseInt(options.getOrDefault(key,
                String.valueOf(fallback)));
    }
}
