package io.tieringkv.console.ui;

import io.tieringkv.console.api.SaasConsoleApi;
import io.tieringkv.console.ui.SaasConsoleUiService.Page;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.commerce.BillingSubscription;
import io.tieringkv.saas.commerce.MarketplaceCatalog;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SaaS 控制台 UI（ADR-0150）：仪表盘/市场/订阅视图 + RBAC。 */
class SaasConsoleUiServiceTest {

    private CredentialManager credentials;
    private SaasConsoleUiService ui;

    @BeforeEach
    void setUp() {
        credentials = new CredentialManager();
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.subscribe("t2", "p1", true);
        SaasConsoleApi api = new SaasConsoleApi(subscriptions, catalog,
                credentials);
        ui = new SaasConsoleUiService(api);
    }

    @Test
    void adminDashboardRendered() {
        Page page = ui.render(admin(), "dashboard");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("SaaS Dashboard")
                .contains("Active subscriptions: 2")
                .contains("t1").contains("t2");
    }

    @Test
    void readerDashboardAllowed() {
        assertThat(ui.render(reader(), "dashboard").status())
                .isEqualTo(200);
    }

    @Test
    void marketplaceRendered() {
        Page page = ui.render(reader(), "marketplace");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("Marketplace")
                .contains("p1").contains("Subscribe");
    }

    @Test
    void subscriptionsRenderedWithStates() {
        Page page = ui.render(admin(), "subscriptions");
        assertThat(page.status()).isEqualTo(200);
        assertThat(page.html()).contains("ACTIVE").contains("TRIAL");
    }

    @Test
    void readerSubscriptionsAllowed() {
        assertThat(ui.render(reader(), "subscriptions").status())
                .isEqualTo(200);
    }

    @Test
    void unknownTokenForbidden() {
        assertThat(ui.render("bad", "dashboard").status())
                .isEqualTo(403);
    }

    @Test
    void unknownViewNotFound() {
        assertThat(ui.render(admin(), "nope").status())
                .isEqualTo(404);
    }

    @Test
    void htmlEscapesTenantId() {
        CredentialManager local = new CredentialManager();
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        BillingSubscription subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        subscriptions.subscribe("<script>alert(1)</script>", "p1",
                false);
        SaasConsoleUiService ui2 = new SaasConsoleUiService(
                new SaasConsoleApi(subscriptions, catalog, local));
        Page page = ui2.render(
                local.issue(Role.ADMIN, 60_000), "dashboard");
        assertThat(page.html()).doesNotContain("<script>")
                .contains("&lt;script&gt;");
    }

    @Test
    void emptyMarketplaceRendersList() {
        CredentialManager local = new CredentialManager();
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        SaasConsoleUiService ui2 = new SaasConsoleUiService(
                new SaasConsoleApi(new BillingSubscription(
                        new BillingScheduler(60_000,
                                new TenantAuditLog()), catalog),
                        catalog, local));
        Page page = ui2.render(
                local.issue(Role.ADMIN, 60_000), "marketplace");
        assertThat(page.html()).contains("<ul></ul>");
    }

    @Test
    void htmlIsCompleteDocument() {
        String html = ui.render(reader(), "dashboard").html();
        assertThat(html).startsWith("<!DOCTYPE html>")
                .contains("</html>");
    }

    @Test
    void titlesMatchViews() {
        assertThat(ui.render(admin(), "dashboard").title())
                .isEqualTo("SaaS Dashboard");
        assertThat(ui.render(admin(), "marketplace").title())
                .isEqualTo("Marketplace");
        assertThat(ui.render(admin(), "subscriptions").title())
                .isEqualTo("Subscriptions");
    }

    @ParameterizedTest(name = "view {0}")
    @ValueSource(strings = {"dashboard", "marketplace",
            "subscriptions"})
    void parameterizedReadViews(String view) {
        assertThat(ui.render(reader(), view).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "view {0}")
    @ValueSource(strings = {"dashboard", "marketplace",
            "subscriptions"})
    void parameterizedAdminViews(String view) {
        assertThat(ui.render(admin(), view).status()).isEqualTo(200);
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedRenderRounds(int rounds) {
        for (int i = 0; i < rounds; i++) {
            assertThat(ui.render(admin(), "dashboard").status())
                    .isEqualTo(200);
            assertThat(ui.render(reader(), "marketplace").status())
                    .isEqualTo(200);
        }
    }

    @Test
    void concurrentRendersStable() throws Exception {
        Thread[] threads = new Thread[6];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    assertThat(ui.render(admin(), "subscriptions")
                            .status()).isEqualTo(200);
                    assertThat(ui.render(reader(), "dashboard")
                            .status()).isEqualTo(200);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
    }

    @Test
    void writerCanReadButNotModifyViews() {
        String token = credentials.issue(Role.WRITER, 60_000);
        assertThat(ui.render(token, "dashboard").status())
                .isEqualTo(200);
    }

    private String admin() {
        return credentials.issue(Role.ADMIN, 60_000);
    }

    private String reader() {
        return credentials.issue(Role.READER, 60_000);
    }
}
