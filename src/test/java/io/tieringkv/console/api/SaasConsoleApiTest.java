package io.tieringkv.console.api;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.commerce.BillingSubscription;
import io.tieringkv.saas.commerce.MarketplaceCatalog;
import io.tieringkv.saas.commerce.Subscription;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 控制台 API（ADR-0150）：订阅/计费/市场 + RBAC。 */
class SaasConsoleApiTest {

    private CredentialManager credentials;
    private BillingSubscription subscriptions;
    private MarketplaceCatalog catalog;
    private SaasConsoleApi api;

    @BeforeEach
    void setUp() {
        credentials = new CredentialManager();
        catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        catalog.registerPlan(new BillingPlan("p2", Map.of(
                UsageMeter.MeterType.STORAGE_GB, 1.0)));
        subscriptions = new BillingSubscription(
                new BillingScheduler(60_000, new TenantAuditLog()),
                catalog);
        api = new SaasConsoleApi(subscriptions, catalog, credentials);
    }

    @Test
    void marketplaceListedForReader() {
        assertThat(api.marketplace(reader())).containsExactlyInAnyOrder(
                "p1", "p2");
    }

    @Test
    void marketplaceRequiresRead() {
        assertThatThrownBy(() -> api.marketplace("bad"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void subscribeRequiresAdmin() {
        assertThatThrownBy(() -> api.subscribe(reader(),
                "t1", "p1", false))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void adminSubscribeAndStatus() {
        Subscription.Snapshot snapshot = api.subscribe(
                admin(), "t1", "p1", false);
        assertThat(snapshot.state()).isEqualTo(
                Subscription.State.ACTIVE);
        assertThat(api.status(reader(), "t1").planId())
                .isEqualTo("p1");
    }

    @Test
    void subscribeTrial() {
        Subscription.Snapshot snapshot = api.subscribe(
                admin(), "t1", "p1", true);
        assertThat(snapshot.state()).isEqualTo(
                Subscription.State.TRIAL);
    }

    @Test
    void unknownPlanRejected() {
        assertThatThrownBy(() -> api.subscribe(admin(),
                "t1", "missing", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activateTrial() {
        api.subscribe(admin(), "t1", "p1", true);
        assertThat(api.activate(admin(), "t1").state())
                .isEqualTo(Subscription.State.ACTIVE);
    }

    @Test
    void activateRequiresAdmin() {
        api.subscribe(admin(), "t1", "p1", true);
        assertThatThrownBy(() -> api.activate(reader(), "t1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void cancelSubscription() {
        api.subscribe(admin(), "t1", "p1", false);
        assertThat(api.cancel(admin(), "t1").state())
                .isEqualTo(Subscription.State.CANCELED);
    }

    @Test
    void cancelRequiresAdmin() {
        api.subscribe(admin(), "t1", "p1", false);
        assertThatThrownBy(() -> api.cancel(reader(), "t1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rollProducesInvoice() {
        api.subscribe(admin(), "t1", "p1", false);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 100);
        assertThat(api.roll(admin(), "t1")).isPresent();
    }

    @Test
    void rollTrialNoInvoice() {
        api.subscribe(admin(), "t1", "p1", true);
        assertThat(api.roll(admin(), "t1")).isEmpty();
    }

    @Test
    void rollRequiresAdmin() {
        api.subscribe(admin(), "t1", "p1", false);
        assertThatThrownBy(() -> api.roll(reader(), "t1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void subscriptionListAndCount() {
        api.subscribe(admin(), "t1", "p1", false);
        api.subscribe(admin(), "t2", "p2", true);
        assertThat(api.subscriptions(reader()))
                .containsExactlyInAnyOrder("t1", "t2");
        assertThat(api.count(reader())).isEqualTo(2);
    }

    @Test
    void subscriptionListRequiresRead() {
        assertThatThrownBy(() -> api.subscriptions("bad"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void statusRequiresRead() {
        api.subscribe(admin(), "t1", "p1", false);
        assertThatThrownBy(() -> api.status("bad", "t1"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void statusUnknownTenantRejected() {
        assertThatThrownBy(() -> api.status(reader(), "missing"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void rollAdvancesCycle() {
        api.subscribe(admin(), "t1", "p1", false);
        api.roll(admin(), "t1");
        assertThat(api.status(reader(), "t1").cycle()).isEqualTo(1);
    }

    @Test
    void duplicateSubscribeRejected() {
        api.subscribe(admin(), "t1", "p1", false);
        assertThatThrownBy(() -> api.subscribe(admin(),
                "t1", "p1", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "plan {0}")
    @ValueSource(strings = {"p1", "p2"})
    void parameterizedPlans(String planId) {
        Subscription.Snapshot snapshot = api.subscribe(admin(),
                "t-" + planId, planId, false);
        assertThat(snapshot.planId()).isEqualTo(planId);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedTenantCount(int count) {
        for (int i = 0; i < count; i++) {
            api.subscribe(admin(), "t" + i, "p1", false);
        }
        assertThat(api.count(reader())).isEqualTo(count);
    }

    @ParameterizedTest(name = "trial {0}")
    @CsvSource({"true,TRIAL", "false,ACTIVE"})
    void parameterizedTrialStates(boolean trial, String state) {
        Subscription.Snapshot snapshot = api.subscribe(admin(),
                "t1", "p1", trial);
        assertThat(snapshot.state().name()).isEqualTo(state);
    }

    @Test
    void concurrentSubscribeAndRoll() throws Exception {
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 20; i++) {
                api.subscribe(admin(), "t" + i, "p1", false);
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                api.marketplace(reader());
                api.count(reader());
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(api.count(reader())).isEqualTo(20);
    }

    @Test
    void invoiceCarriesPlanAndTenant() {
        api.subscribe(admin(), "t1", "p1", false);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 10);
        Invoice invoice = api.roll(admin(), "t1").orElseThrow();
        assertThat(invoice.tenantId()).isEqualTo("t1");
        assertThat(invoice.planId()).isEqualTo("p1");
    }

    private String admin() {
        return credentials.issue(Role.ADMIN, 60_000);
    }

    private String reader() {
        return credentials.issue(Role.READER, 60_000);
    }
}
