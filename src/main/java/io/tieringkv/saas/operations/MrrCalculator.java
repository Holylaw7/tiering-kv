package io.tieringkv.saas.operations;

import io.tieringkv.saas.billing.Invoice;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** MRR 计算（ADR-0155）：活跃订阅周期收入。 */
public final class MrrCalculator {

    private final Map<String, Double> amounts =
            new ConcurrentHashMap<>();

    public void setMonthlyAmount(String tenantId, double amount) {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException(
                    "tenantId required");
        }
        if (amount < 0) {
            throw new IllegalArgumentException(
                    "amount must be non-negative");
        }
        amounts.put(tenantId, amount);
    }

    /** 从周期账单记录月经常性收入。 */
    public void record(Invoice invoice) {
        if (invoice == null) {
            throw new IllegalArgumentException(
                    "invoice required");
        }
        setMonthlyAmount(invoice.tenantId(), invoice.total());
    }

    /** 仅活跃订阅的 MRR。 */
    public double mrr(Set<String> activeTenants) {
        return activeTenants.stream()
                .mapToDouble(tenant -> amounts.getOrDefault(
                        tenant, 0.0))
                .sum();
    }

    public double total() {
        return amounts.values().stream().mapToDouble(
                Double::doubleValue).sum();
    }

    public Map<String, Double> byTenant() {
        return Map.copyOf(amounts);
    }

    public double amount(String tenantId) {
        return amounts.getOrDefault(tenantId, 0.0);
    }
}
