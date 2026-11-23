package io.tieringkv.saas.billing;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** 账单滚动结算（ADR-0136）：周期滚动 → 冻结 → 导出 → 审计。 */
public final class BillingScheduler {

    private final long periodMillis;
    private final MeteredBilling billing;
    private final TenantAuditLog audit;
    private final Map<String, UsageMeter> meters =
            new ConcurrentHashMap<>();

    public BillingScheduler(long periodMillis, TenantAuditLog audit) {
        this.periodMillis = Math.max(1, periodMillis);
        this.billing = new MeteredBilling();
        this.audit = audit;
    }

    public UsageMeter meter(String tenantId) {
        return meters.computeIfAbsent(tenantId,
                ignored -> new UsageMeter());
    }

    public Invoice roll(String tenantId, BillingPlan plan,
                        long cycle) {
        UsageMeter meter = meter(tenantId);
        long start = cycle * periodMillis;
        long end = start + periodMillis;
        BillingPeriod period = new BillingPeriod(start, end, true);
        List<Invoice.LineItem> items = new ArrayList<>();
        for (UsageMeter.MeterType type : UsageMeter.MeterType.values()) {
            long quantity = meter.get(type);
            Double price = plan.prices().get(type);
            if (price != null && quantity > 0) {
                items.add(new Invoice.LineItem(type, quantity,
                        price, quantity * price));
            }
        }
        Invoice invoice = new Invoice(tenantId, plan.planId(),
                period, items);
        audit.record(tenantId, "billing-roll:" + cycle);
        meter.reset();
        return invoice;
    }
}
