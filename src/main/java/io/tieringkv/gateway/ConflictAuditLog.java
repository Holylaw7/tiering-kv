package io.tieringkv.gateway;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 冲突审计日志（ADR-0141）：region/key/ts/winner。 */
public final class ConflictAuditLog {

    public record ConflictEntry(String region, String key,
                                long timestamp, String winner) {
    }

    private final List<ConflictEntry> entries =
            new CopyOnWriteArrayList<>();

    public void audit(String region, String key, String winner) {
        entries.add(new ConflictEntry(region, key,
                System.currentTimeMillis(), winner));
    }

    public List<ConflictEntry> entries() {
        return List.copyOf(entries);
    }

    public List<ConflictEntry> byKey(String key) {
        return entries.stream()
                .filter(entry -> entry.key().equals(key))
                .toList();
    }

    public int size() {
        return entries.size();
    }
}
