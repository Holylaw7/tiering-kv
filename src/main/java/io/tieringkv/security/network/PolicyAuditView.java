package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyAudit.AuditEvent;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 策略审计视图（ADR-0176）：按租户/动作聚合。 */
public final class PolicyAuditView {

    public Map<String, Long> byTenant(NetworkPolicyAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit required");
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        for (AuditEvent event : audit.events()) {
            result.merge(event.tenantA(), 1L, Long::sum);
            result.merge(event.tenantB(), 1L, Long::sum);
        }
        return Map.copyOf(result);
    }

    public Map<String, Long> byAction(NetworkPolicyAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit required");
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        for (AuditEvent event : audit.events()) {
            result.merge(event.action(), 1L, Long::sum);
        }
        return Map.copyOf(result);
    }

    public Map<String, Long> byTenantAction(
            NetworkPolicyAudit audit) {
        if (audit == null) {
            throw new IllegalArgumentException("audit required");
        }
        Map<String, Long> result = new ConcurrentHashMap<>();
        for (AuditEvent event : audit.events()) {
            String key = event.tenantA() + ":" + event.action();
            result.merge(key, 1L, Long::sum);
        }
        return Map.copyOf(result);
    }
}
