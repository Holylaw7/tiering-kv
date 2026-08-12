package io.tieringkv.console.api;

import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.commerce.BillingSubscription;
import io.tieringkv.saas.commerce.MarketplaceCatalog;
import io.tieringkv.saas.commerce.Subscription;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/** SaaS 控制台 API（ADR-0150）：订阅/计费/市场端点（RBAC）。 */
public final class SaasConsoleApi {

    private final BillingSubscription subscriptions;
    private final MarketplaceCatalog catalog;
    private final CredentialManager credentials;

    public SaasConsoleApi(BillingSubscription subscriptions,
                          MarketplaceCatalog catalog,
                          CredentialManager credentials) {
        this.subscriptions = subscriptions;
        this.catalog = catalog;
        this.credentials = credentials;
    }

    public List<String> marketplace(String token) {
        require(token, Permission.READ);
        List<String> items = new ArrayList<>();
        items.addAll(catalog.planIds());
        items.addAll(catalog.templateIds());
        return items;
    }

    public Subscription.Snapshot subscribe(String token,
                                           String tenantId,
                                           String planId,
                                           boolean trial) {
        require(token, Permission.ADMIN);
        return subscriptions.subscribe(tenantId, planId, trial);
    }

    public Subscription.Snapshot activate(String token,
                                          String tenantId) {
        require(token, Permission.ADMIN);
        return subscriptions.activate(tenantId);
    }

    public Subscription.Snapshot cancel(String token,
                                        String tenantId) {
        require(token, Permission.ADMIN);
        return subscriptions.cancel(tenantId);
    }

    public Subscription.Snapshot status(String token,
                                        String tenantId) {
        require(token, Permission.READ);
        return subscriptions.status(tenantId);
    }

    public Optional<Invoice> roll(String token, String tenantId) {
        require(token, Permission.ADMIN);
        return subscriptions.roll(tenantId, cycle(token, tenantId));
    }

    public List<String> subscriptions(String token) {
        require(token, Permission.READ);
        return subscriptions.tenants();
    }

    public int count(String token) {
        require(token, Permission.READ);
        return subscriptions.count();
    }

    private long cycle(String token, String tenantId) {
        return subscriptions.status(tenantId).cycle();
    }

    private void require(String token, Permission permission) {
        credentials.require(token, permission);
    }
}
