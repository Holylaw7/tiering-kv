package io.tieringkv.console;

import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;

import java.util.List;

/** 企业控制台 API（ADR-0137）：租户/集群/指标/告警查询（RBAC）。 */
public final class ConsoleApi {

    private final TenantRegistry tenants;
    private final Phase28Metrics metrics;
    private final AlertManager alerts;
    private final CredentialManager credentials;

    public ConsoleApi(TenantRegistry tenants, Phase28Metrics metrics,
                      AlertManager alerts,
                      CredentialManager credentials) {
        this.tenants = tenants;
        this.metrics = metrics;
        this.alerts = alerts;
        this.credentials = credentials;
    }

    public List<String> listTenants(String token) {
        require(token, Permission.ADMIN);
        return tenants.tenantIds();
    }

    public void createTenant(String token, ClusterTenant tenant) {
        require(token, Permission.ADMIN);
        tenants.register(tenant);
    }

    public java.util.Map<String, Long> metrics(String token) {
        require(token, Permission.READ);
        return metrics.snapshot();
    }

    public List<String> alerts(String token) {
        require(token, Permission.ADMIN);
        return alerts.evaluate(metrics.snapshot());
    }

    private void require(String token, Permission permission) {
        credentials.require(token, permission);
    }
}
