package io.tieringkv.webconsole.support;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * INFO 文本解析器。
 *
 * <p>输入形如：
 * <pre>
 * # Server
 * redis_version:4.1.0
 * ...
 * # Keyspace
 * db0:keys=12,expires=0,avg_ttl=0
 * </pre>
 * 输出 section → (key → value) 的两级映射，同时提供扁平视图。
 */
public final class InfoParser {

    private InfoParser() {
    }

    public static Map<String, Map<String, String>> parse(String infoText) {
        Map<String, Map<String, String>> sections = new LinkedHashMap<>();
        if (infoText == null || infoText.isBlank()) {
            return sections;
        }
        String current = "default";
        Map<String, String> bucket = sections.computeIfAbsent(current, key -> new LinkedHashMap<>());
        for (String rawLine : infoText.split("\r?\n")) {
            String line = rawLine.trim();
            if (line.isEmpty()) {
                continue;
            }
            if (line.startsWith("#")) {
                current = line.substring(1).trim();
                bucket = sections.computeIfAbsent(current, key -> new LinkedHashMap<>());
                continue;
            }
            int split = line.indexOf(':');
            if (split <= 0) {
                continue;
            }
            bucket.put(line.substring(0, split).trim(), line.substring(split + 1).trim());
        }
        return sections;
    }

    /** 扁平化：同名键以首次出现为准。 */
    public static Map<String, String> flatten(Map<String, Map<String, String>> sections) {
        Map<String, String> flat = new LinkedHashMap<>();
        sections.values().forEach(flat::putAll);
        return flat;
    }

    public static String get(Map<String, Map<String, String>> sections,
                             String section, String key) {
        Map<String, String> bucket = sections.get(section);
        return bucket == null ? null : bucket.get(key);
    }

    public static long longValue(Map<String, Map<String, String>> sections,
                                 String section, String key, long fallback) {
        String value = get(sections, section, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    public static double doubleValue(Map<String, Map<String, String>> sections,
                                     String section, String key, double fallback) {
        String value = get(sections, section, key);
        if (value == null) {
            return fallback;
        }
        try {
            return Double.parseDouble(value);
        } catch (NumberFormatException ex) {
            return fallback;
        }
    }

    /** 解析 Keyspace section 的 dbN:keys=..,expires=..,avg_ttl=.. 行。 */
    public static long keyCount(Map<String, Map<String, String>> sections) {
        Map<String, String> keyspace = sections.get("Keyspace");
        if (keyspace == null) {
            return 0;
        }
        long total = 0;
        for (String value : keyspace.values()) {
            for (String part : value.split(",")) {
                String trimmed = part.trim();
                if (trimmed.startsWith("keys=")) {
                    try {
                        total += Long.parseLong(trimmed.substring("keys=".length()));
                    } catch (NumberFormatException ignored) {
                        // 忽略异常字段
                    }
                }
            }
        }
        return total;
    }
}
