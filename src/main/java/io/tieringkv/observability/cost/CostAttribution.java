package io.tieringkv.observability.cost;

import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;

/** 成本归因（ADR-0154）：租户/域/云 → 资源成本。 */
public final class CostAttribution {

    /** 成本项。 */
    public record CostEntry(String tenantId, String domainId,
                            String cloud, String resource,
                            double cost) {

        public CostEntry {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "tenantId required");
            }
            if (cost < 0) {
                throw new IllegalArgumentException(
                        "cost must be non-negative");
            }
        }
    }

    private final List<CostEntry> entries =
            new CopyOnWriteArrayList<>();

    public void add(CostEntry entry) {
        if (entry == null) {
            throw new IllegalArgumentException("entry required");
        }
        entries.add(entry);
    }

    public double total() {
        return entries.stream().mapToDouble(CostEntry::cost).sum();
    }

    public Map<String, Double> byTenant() {
        return aggregate(CostEntry::tenantId);
    }

    public Map<String, Double> byCloud() {
        return aggregate(CostEntry::cloud);
    }

    public Map<String, Double> byDomain() {
        return aggregate(CostEntry::domainId);
    }

    public List<CostEntry> forTenant(String tenantId) {
        return entries.stream()
                .filter(entry -> entry.tenantId().equals(tenantId))
                .toList();
    }

    public int size() {
        return entries.size();
    }

    public void clear() {
        entries.clear();
    }

    private Map<String, Double> aggregate(
            java.util.function.Function<CostEntry, String> key) {
        Map<String, Double> result = new ConcurrentHashMap<>();
        entries.forEach(entry -> result.merge(key.apply(entry),
                entry.cost(), Double::sum));
        return Map.copyOf(result);
    }
}
