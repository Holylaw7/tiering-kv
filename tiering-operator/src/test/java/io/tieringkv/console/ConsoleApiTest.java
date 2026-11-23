package io.tieringkv.console;

import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.AlertRule;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 企业控制台（ADR-0137）：API + RBAC。 */
class ConsoleApiTest {

    private static ConsoleApi api() {
        TenantRegistry tenants = new TenantRegistry();
        tenants.register(new ClusterTenant("t1", "prod", 3, 100));
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.gauge("lag", 50);
        AlertManager alerts = new AlertManager(List.of(
                new AlertRule("lag", 100, true,
                        AlertRule.Level.WARN)));
        return new ConsoleApi(tenants, metrics, alerts,
                new CredentialManager());
    }

    @Test
    void adminListsTenants() {
        CredentialManager credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        api.createTenant(credentials.issue(Role.ADMIN, 60_000),
                new ClusterTenant("t1", "prod", 3, 100));
        assertThat(api.listTenants(
                credentials.issue(Role.ADMIN, 60_000)))
                .contains("t1");
    }

    @Test
    void readerCannotListTenants() {
        CredentialManager credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        assertThatThrownBy(() -> api.listTenants(
                credentials.issue(Role.READER, 60_000)))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void readerCanReadMetrics() {
        CredentialManager credentials = new CredentialManager();
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.gauge("lag", 5);
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                metrics, new AlertManager(List.of()), credentials);
        assertThat(api.metrics(
                credentials.issue(Role.READER, 60_000)))
                .containsEntry("lag", 5L);
    }

    @Test
    void adminSeesAlerts() {
        CredentialManager credentials = new CredentialManager();
        TenantRegistry tenants = new TenantRegistry();
        tenants.register(new ClusterTenant("t1", "prod", 3, 100));
        Phase28Metrics metrics = new Phase28Metrics();
        metrics.gauge("lag", 50);
        ConsoleApi api = new ConsoleApi(tenants, metrics,
                new AlertManager(List.of(new AlertRule(
                        "lag", 100, true, AlertRule.Level.WARN))),
                credentials);
        List<String> alerts = api.alerts(
                credentials.issue(Role.ADMIN, 60_000));
        assertThat(alerts).isEmpty(); // lag=50 < 100
    }

    @ParameterizedTest(name = "role {0}")
    @ValueSource(strings = {"READER", "WRITER", "ADMIN"})
    void parameterizedRoles(String roleName) {
        CredentialManager credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        Role role = Role.valueOf(roleName);
        String token = credentials.issue(role, 60_000);
        if (role == Role.ADMIN) {
            assertThat(api.metrics(token)).isEmpty();
            assertThat(api.listTenants(token)).isEmpty();
        } else {
            assertThatThrownBy(() -> api.listTenants(token))
                    .isInstanceOf(SecurityException.class);
        }
    }

    @Test
    void unknownTokenRejected() {
        ConsoleApi api = api();
        assertThatThrownBy(() -> api.metrics("bad"))
                .isInstanceOf(SecurityException.class);
    }
}
