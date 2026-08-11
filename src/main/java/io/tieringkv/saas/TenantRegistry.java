package io.tieringkv.saas;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SaaS 租户注册表（ADR-0118）：注册/列表/状态。 */
public final class TenantRegistry {

    public enum TenantState {
        ACTIVE,
        SUSPENDED,
        DELETED
    }

    public record TenantRecord(ClusterTenant tenant, TenantState state) {
    }

    private final Map<String, TenantRecord> tenants =
            new ConcurrentHashMap<>();

    public void register(ClusterTenant tenant) {
        if (tenants.putIfAbsent(tenant.tenantId(),
                new TenantRecord(tenant, TenantState.ACTIVE)) != null) {
            throw new IllegalArgumentException(
                    "tenant already exists: " + tenant.tenantId());
        }
    }

    public TenantRecord get(String tenantId) {
        return tenants.get(tenantId);
    }

    public boolean suspend(String tenantId) {
        TenantRecord record = tenants.get(tenantId);
        if (record == null) {
            return false;
        }
        tenants.put(tenantId, new TenantRecord(record.tenant(),
                TenantState.SUSPENDED));
        return true;
    }

    public boolean delete(String tenantId) {
        return tenants.remove(tenantId) != null;
    }

    public List<String> tenantIds() {
        return List.copyOf(tenants.keySet());
    }

    public int size() {
        return tenants.size();
    }
}
