package io.tieringkv.security.network;

import io.tieringkv.security.network.NetworkPolicyDsl.PolicyRule;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 网络策略审计（ADR-0176）：变更事件记录。 */
public final class NetworkPolicyAudit {

    /** 审计事件：租户对 + 动作 + 来源 + 时间。 */
    public record AuditEvent(String tenantA, String tenantB,
                             String action, String source,
                             long timestampMillis) {

        public AuditEvent {
            if (tenantA == null || tenantA.isBlank()
                    || tenantB == null || tenantB.isBlank()) {
                throw new IllegalArgumentException(
                        "tenants required");
            }
            if (action == null || action.isBlank()) {
                throw new IllegalArgumentException(
                        "action required");
            }
            if (source == null || source.isBlank()) {
                throw new IllegalArgumentException(
                        "source required");
            }
        }
    }

    private final List<AuditEvent> events =
            new CopyOnWriteArrayList<>();

    /** 记录策略规则事件。 */
    public void record(String source, PolicyRule rule,
                       long timestampMillis) {
        if (rule == null) {
            throw new IllegalArgumentException("rule required");
        }
        events.add(new AuditEvent(rule.from(), rule.to(),
                rule.action(), source, timestampMillis));
    }

    public List<AuditEvent> events() {
        return List.copyOf(events);
    }

    public List<AuditEvent> forTenant(String tenantId) {
        return events.stream()
                .filter(event -> event.tenantA().equals(tenantId)
                        || event.tenantB().equals(tenantId))
                .toList();
    }

    public List<AuditEvent> since(long timestampMillis) {
        return events.stream()
                .filter(event -> event.timestampMillis()
                        >= timestampMillis)
                .toList();
    }

    public int size() {
        return events.size();
    }
}
