package io.tieringkv.console.ui;

import io.tieringkv.console.ConsoleApi;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.AlertRule;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 控制台 UI 原型（ADR-0146）：HTML 视图 + RBAC 门控 + 自服务表单。 */
class ConsoleUiServiceTest {

    private CredentialManager credentials;
    private Phase28Metrics metrics;
    private BillingScheduler billing;
    private ConsoleUiService ui;

    @BeforeEach
    void setUp() {
        credentials = new CredentialManager();
        TenantRegistry tenants = new TenantRegistry();
        tenants.register(new ClusterTenant("t1", "prod", 3, 100));
        tenants.register(new ClusterTenant("t2", "dev", 2, 50));
        metrics = new Phase28Metrics();
        metrics.gauge("storage.used_gb", 42);
        metrics.increment("req.count");
        AlertManager alerts = new AlertManager(List.of(
                new AlertRule("storage.used_gb", 40, true,
                        AlertRule.Level.WARN)));
        billing = new BillingScheduler(60_000, new TenantAuditLog());
        billing.meter("t1").record(UsageMeter.MeterType.REQUESTS, 1200);
        billing.meter("t1").record(UsageMeter.MeterType.EGRESS_GB, 7);
        ConsoleApi api = new ConsoleApi(tenants, metrics, alerts,
                credentials);
        ui = new ConsoleUiService(api, billing, credentials);
    }

    @Test
    void adminOverviewRendered() {
        ConsoleUiService.Page page = ui.render(admin(), "overview");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("Overview")
                .contains("Tenants: 2")
                .contains("storage.used_gb");
    }

    @Test
    void adminTenantsViewContainsIds() {
        ConsoleUiService.Page page = ui.render(admin(), "tenants");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("t1").contains("t2")
                .contains("Create");
    }

    @Test
    void adminBillingViewContainsMeterQuantities() {
        ConsoleUiService.Page page = ui.render(admin(), "billing");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("Billing")
                .contains("1200").contains("7");
    }

    @Test
    void adminMetricsViewContainsValues() {
        ConsoleUiService.Page page = ui.render(admin(), "metrics");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("req.count").contains("42");
    }

    @Test
    void adminAlertsViewContainsAlert() {
        ConsoleUiService.Page page = ui.render(admin(), "alerts");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("WARN:storage.used_gb");
    }

    @Test
    void readerOverviewAllowed() {
        ConsoleUiService.Page page = ui.render(reader(), "overview");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("Overview")
                .contains("Tenants: 0"); // 租户视图降级
    }

    @Test
    void readerMetricsAllowed() {
        ConsoleUiService.Page page = ui.render(reader(), "metrics");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("req.count");
    }

    @Test
    void readerTenantsForbidden() {
        ConsoleUiService.Page page = ui.render(reader(), "tenants");
        assertThat(page.status()).isEqualTo(403);
        assertThat(page.html()).contains("403");
    }

    @Test
    void readerBillingForbidden() {
        ConsoleUiService.Page page = ui.render(reader(), "billing");
        assertThat(page.status()).isEqualTo(403);
    }

    @Test
    void readerAlertsForbidden() {
        ConsoleUiService.Page page = ui.render(reader(), "alerts");
        assertThat(page.status()).isEqualTo(403);
    }

    @Test
    void unknownTokenForbidden() {
        ConsoleUiService.Page page = ui.render("bad-token", "metrics");
        assertThat(page.status()).isEqualTo(403);
    }

    @Test
    void unknownViewNotFound() {
        ConsoleUiService.Page page = ui.render(admin(), "nope");
        assertThat(page.status()).isEqualTo(404);
    }

    @Test
    void htmlEscapesTenantId() {
        TenantRegistry tenants = new TenantRegistry();
        tenants.register(new ClusterTenant("<script>alert(1)</script>",
                "evil", 1, 10));
        ConsoleApi api = new ConsoleApi(tenants, new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        ConsoleUiService ui2 = new ConsoleUiService(api,
                new BillingScheduler(60_000, new TenantAuditLog()),
                credentials);
        ConsoleUiService.Page page = ui2.render(admin(), "tenants");
        assertThat(page.html()).doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    void emptyTenantsRendersTable() {
        TenantRegistry tenants = new TenantRegistry();
        ConsoleApi api = new ConsoleApi(tenants, new Phase28Metrics(),
                new AlertManager(List.of()), credentials);
        ConsoleUiService ui2 = new ConsoleUiService(api,
                new BillingScheduler(60_000, new TenantAuditLog()),
                credentials);
        ConsoleUiService.Page page = ui2.render(admin(), "tenants");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("<table>");
    }

    @Test
    void emptyAlertsRendersEmptyList() {
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(), new AlertManager(List.of()),
                credentials);
        ConsoleUiService ui2 = new ConsoleUiService(api,
                new BillingScheduler(60_000, new TenantAuditLog()),
                credentials);
        ConsoleUiService.Page page = ui2.render(admin(), "alerts");
        assertThat(page.html()).contains("<ul></ul>");
    }

    @Test
    void pageTitleMatchesView() {
        assertThat(ui.render(admin(), "billing").title())
                .isEqualTo("Billing");
        assertThat(ui.render(admin(), "tenants").title())
                .isEqualTo("Tenants");
    }

    @Test
    void htmlIsCompleteDocument() {
        String html = ui.render(admin(), "metrics").html();
        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("</html>");
    }

    @ParameterizedTest(name = "view {0}")
    @ValueSource(strings = {"overview", "tenants", "billing",
            "metrics", "alerts"})
    void parameterizedAdminViews(String view) {
        assertThat(ui.render(admin(), view).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "view {0}")
    @ValueSource(strings = {"tenants", "billing", "alerts"})
    void parameterizedReaderAdminViewsForbidden(String view) {
        assertThat(ui.render(reader(), view).status()).isEqualTo(403);
    }

    @ParameterizedTest(name = "view {0}")
    @ValueSource(strings = {"overview", "metrics"})
    void parameterizedReaderReadViewsAllowed(String view) {
        assertThat(ui.render(reader(), view).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "views {0}/{1}")
    @CsvSource({"1,metrics", "2,overview", "3,tenants"})
    void parameterizedSequentialRenders(int rounds, String view) {
        for (int i = 0; i < rounds; i++) {
            assertThat(ui.render(admin(), view).status())
                    .isEqualTo(200);
        }
    }

    @Test
    void concurrentRendersStable() throws Exception {
        List<Thread> threads = new java.util.ArrayList<>();
        for (int i = 0; i < 8; i++) {
            Thread thread = new Thread(() -> {
                for (int r = 0; r < 50; r++) {
                    assertThat(ui.render(admin(), "overview").status())
                            .isEqualTo(200);
                    assertThat(ui.render(reader(), "metrics").status())
                            .isEqualTo(200);
                }
            });
            threads.add(thread);
            thread.start();
        }
        for (Thread thread : threads) {
            thread.join(15_000);
        }
    }

    @Test
    void writerCannotAccessAdminViews() {
        String token = credentials.issue(Role.WRITER, 60_000);
        assertThat(ui.render(token, "tenants").status()).isEqualTo(403);
        assertThat(ui.render(token, "metrics").status()).isEqualTo(200);
    }

    private String admin() {
        return credentials.issue(Role.ADMIN, 60_000);
    }

    private String reader() {
        return credentials.issue(Role.READER, 60_000);
    }
}
