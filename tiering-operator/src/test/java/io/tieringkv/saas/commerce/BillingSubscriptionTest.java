package io.tieringkv.saas.commerce;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.commerce.Subscription.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 计费订阅（ADR-0146）：订阅生命周期 + 计费周期联动。 */
class BillingSubscriptionTest {

    @Test
    void subscribeActive() {
        BillingSubscription subscriptions = fixture();
        Subscription.Snapshot snapshot = subscriptions.subscribe(
                "t1", "p1", false);
        assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
        assertThat(subscriptions.status("t1").planId()).isEqualTo("p1");
    }

    @Test
    void subscribeTrial() {
        BillingSubscription subscriptions = fixture();
        Subscription.Snapshot snapshot = subscriptions.subscribe(
                "t1", "p1", true);
        assertThat(snapshot.state()).isEqualTo(State.TRIAL);
    }

    @Test
    void duplicateSubscribeRejected() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        assertThatThrownBy(() -> subscriptions.subscribe(
                "t1", "p1", false))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void unknownPlanRejected() {
        BillingSubscription subscriptions = fixture();
        assertThatThrownBy(() -> subscriptions.subscribe(
                "t1", "missing", false))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void activateTrial() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", true);
        Subscription.Snapshot snapshot =
                subscriptions.activate("t1");
        assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void cancelActive() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        assertThat(subscriptions.cancel("t1").state())
                .isEqualTo(State.CANCELED);
    }

    @Test
    void activeRollProducesInvoice() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 100);
        Optional<Invoice> invoice = subscriptions.roll("t1", 0);
        assertThat(invoice).isPresent();
        assertThat(invoice.orElseThrow().total()).isEqualTo(1.0);
    }

    @Test
    void trialRollNoInvoiceAndResetsMeter() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", true);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 500);
        assertThat(subscriptions.roll("t1", 0)).isEmpty();
        assertThat(subscriptions.billing().meter("t1").get(
                UsageMeter.MeterType.REQUESTS)).isZero();
    }

    @Test
    void canceledRollRejected() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.cancel("t1");
        assertThatThrownBy(() -> subscriptions.roll("t1", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activeRollRenewsCycle() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.roll("t1", 0);
        assertThat(subscriptions.status("t1").cycle()).isEqualTo(1);
    }

    @Test
    void multipleCyclesAccumulateBilling() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        for (long cycle = 0; cycle < 3; cycle++) {
            subscriptions.billing().meter("t1").record(
                    UsageMeter.MeterType.REQUESTS, 100);
            assertThat(subscriptions.roll("t1", cycle)).isPresent();
        }
        assertThat(subscriptions.status("t1").cycle()).isEqualTo(3);
    }

    @Test
    void statusUnknownTenantRejected() {
        assertThatThrownBy(() -> fixture().status("missing"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void tenantsListed() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.subscribe("t2", "p1", true);
        assertThat(subscriptions.tenants()).containsExactlyInAnyOrder(
                "t1", "t2");
        assertThat(subscriptions.count()).isEqualTo(2);
    }

    @Test
    void cancelDoesNotRemoveTenant() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.cancel("t1");
        assertThat(subscriptions.count()).isEqualTo(1);
        assertThat(subscriptions.status("t1").state())
                .isEqualTo(State.CANCELED);
    }

    @Test
    void rollUnknownTenantRejected() {
        assertThatThrownBy(() -> fixture().roll("missing", 0))
                .isInstanceOf(IllegalStateException.class);
    }

    @ParameterizedTest(name = "cycles {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedRollCycles(int cycles) {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        for (int i = 0; i < cycles; i++) {
            subscriptions.billing().meter("t1").record(
                    UsageMeter.MeterType.REQUESTS, 10);
            assertThat(subscriptions.roll("t1", i)).isPresent();
        }
        assertThat(subscriptions.status("t1").cycle())
                .isEqualTo(cycles);
    }

    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(ints = {1, 100, 1000})
    void parameterizedInvoiceTotals(int quantity) {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        subscriptions.billing().meter("t1").record(
                UsageMeter.MeterType.REQUESTS, quantity);
        Invoice invoice = subscriptions.roll("t1", 0).orElseThrow();
        assertThat(invoice.total()).isEqualTo(quantity * 0.01);
    }

    @Test
    void invoiceCarriesTenantAndPlan() {
        BillingSubscription subscriptions = fixture();
        subscriptions.subscribe("t1", "p1", false);
        Invoice invoice = subscriptions.roll("t1", 0).orElseThrow();
        assertThat(invoice.tenantId()).isEqualTo("t1");
        assertThat(invoice.planId()).isEqualTo("p1");
    }

    @Test
    void concurrentSubscribeAndRoll() throws Exception {
        BillingSubscription subscriptions = fixture();
        for (int i = 0; i < 10; i++) {
            subscriptions.subscribe("t" + i, "p1", false);
        }
        Thread roller = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                subscriptions.billing().meter("t" + i).record(
                        UsageMeter.MeterType.REQUESTS, 1);
                subscriptions.roll("t" + i, 0);
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 10; i++) {
                subscriptions.status("t" + i);
            }
        });
        roller.start();
        reader.start();
        roller.join(10_000);
        reader.join(10_000);
        assertThat(subscriptions.count()).isEqualTo(10);
    }

    private static BillingSubscription fixture() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p1", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01,
                UsageMeter.MeterType.STORAGE_GB, 1.0,
                UsageMeter.MeterType.EGRESS_GB, 0.1)));
        BillingScheduler billing = new BillingScheduler(60_000,
                new TenantAuditLog());
        return new BillingSubscription(billing, catalog);
    }
}
