package io.tieringkv.saas;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** SaaS 审计日志（ADR-0118）：租户操作全记录。 */
public final class TenantAuditLog {

    public record AuditEntry(String tenantId, String action,
                             long timestampMillis) {
    }

    private final List<AuditEntry> entries = new CopyOnWriteArrayList<>();

    public void record(String tenantId, String action) {
        entries.add(new AuditEntry(tenantId, action,
                System.currentTimeMillis()));
    }

    public List<AuditEntry> entries(String tenantId) {
        return entries.stream()
                .filter(entry -> entry.tenantId().equals(tenantId))
                .toList();
    }

    public List<AuditEntry> all() {
        return List.copyOf(entries);
    }

    public int size() {
        return entries.size();
    }
}
