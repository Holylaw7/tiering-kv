package io.tieringkv.saas.commerce;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.billing.Invoice;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/** SaaS 计费订阅（ADR-0146）：订阅生命周期 + BillingScheduler 周期联动。 */
public final class BillingSubscription {

    private final BillingScheduler billing;
    private final MarketplaceCatalog catalog;
    private final Map<String, Subscription.Snapshot> subscriptions =
            new ConcurrentHashMap<>();

    public BillingSubscription(BillingScheduler billing,
                               MarketplaceCatalog catalog) {
        this.billing = billing;
        this.catalog = catalog;
    }

    public Subscription.Snapshot subscribe(String tenantId,
                                           String planId,
                                           boolean trial) {
        if (catalog.plan(planId).isEmpty()) {
            throw new IllegalArgumentException("unknown plan " + planId);
        }
        if (subscriptions.containsKey(tenantId)) {
            throw new IllegalStateException(
                    "tenant already subscribed");
        }
        Subscription.Snapshot snapshot = new Subscription.Snapshot(
                tenantId, planId,
                trial ? Subscription.State.TRIAL
                        : Subscription.State.ACTIVE,
                0, System.currentTimeMillis());
        subscriptions.put(tenantId, snapshot);
        return snapshot;
    }

    public Subscription.Snapshot activate(String tenantId) {
        Subscription.Snapshot next = require(tenantId).activate();
        subscriptions.put(tenantId, next);
        return next;
    }

    public Subscription.Snapshot cancel(String tenantId) {
        Subscription.Snapshot next = require(tenantId).cancel();
        subscriptions.put(tenantId, next);
        return next;
    }

    /** 周期结算：ACTIVE 出账单；TRIAL 免单并重置计量。 */
    public Optional<Invoice> roll(String tenantId, long cycle) {
        Subscription.Snapshot snapshot = require(tenantId);
        if (snapshot.state() == Subscription.State.CANCELED) {
            throw new IllegalStateException(
                    "canceled subscription cannot bill");
        }
        BillingPlan plan = catalog.plan(snapshot.planId()).orElseThrow(
                () -> new IllegalStateException("plan missing"));
        if (snapshot.state() == Subscription.State.TRIAL) {
            billing.meter(tenantId).reset();
            return Optional.empty();
        }
        Invoice invoice = billing.roll(tenantId, plan, cycle);
        subscriptions.put(tenantId, snapshot.renew());
        return Optional.of(invoice);
    }

    public Subscription.Snapshot status(String tenantId) {
        return require(tenantId);
    }

    public List<String> tenants() {
        return List.copyOf(subscriptions.keySet());
    }

    public int count() {
        return subscriptions.size();
    }

    public BillingScheduler billing() {
        return billing;
    }

    private Subscription.Snapshot require(String tenantId) {
        Subscription.Snapshot snapshot = subscriptions.get(tenantId);
        if (snapshot == null) {
            throw new IllegalStateException(
                    "tenant not subscribed");
        }
        return snapshot;
    }
}
