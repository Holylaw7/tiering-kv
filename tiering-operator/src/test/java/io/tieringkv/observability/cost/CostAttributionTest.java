package io.tieringkv.observability.cost;

import io.tieringkv.observability.cost.CostAttribution.CostEntry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 成本归因（ADR-0154）：租户/域/云聚合。 */
class CostAttributionTest {

    @Test
    void addAndTotal() {
        CostAttribution attribution = new CostAttribution();
        attribution.add(new CostEntry("t1", "orders", "aws-us",
                "storage", 10));
        attribution.add(new CostEntry("t1", "payments", "aws-us",
                "compute", 5));
        assertThat(attribution.total()).isEqualTo(15);
        assertThat(attribution.size()).isEqualTo(2);
    }

    @Test
    void byTenant() {
        CostAttribution attribution = attribution();
        assertThat(attribution.byTenant())
                .containsEntry("t1", 15.0)
                .containsEntry("t2", 5.0);
    }

    @Test
    void byCloud() {
        CostAttribution attribution = attribution();
        assertThat(attribution.byCloud())
                .containsEntry("aws-us", 15.0)
                .containsEntry("gcp-us", 5.0);
    }

    @Test
    void byDomain() {
        CostAttribution attribution = attribution();
        assertThat(attribution.byDomain())
                .containsEntry("orders", 10.0)
                .containsEntry("payments", 10.0);
    }

    @Test
    void forTenant() {
        CostAttribution attribution = attribution();
        assertThat(attribution.forTenant("t2")).hasSize(1);
        assertThat(attribution.forTenant("missing")).isEmpty();
    }

    @Test
    void clear() {
        CostAttribution attribution = attribution();
        attribution.clear();
        assertThat(attribution.size()).isZero();
        assertThat(attribution.total()).isZero();
    }

    @Test
    void emptyAggregates() {
        CostAttribution attribution = new CostAttribution();
        assertThat(attribution.byTenant()).isEmpty();
        assertThat(attribution.byCloud()).isEmpty();
        assertThat(attribution.byDomain()).isEmpty();
    }

    @Test
    void negativeCostRejected() {
        assertThatThrownBy(() -> new CostEntry("t1", "d", "c",
                "r", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new CostEntry("", "d", "c",
                "r", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullEntryRejected() {
        assertThatThrownBy(() -> new CostAttribution().add(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "costs {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedCosts(int count) {
        CostAttribution attribution = new CostAttribution();
        for (int i = 0; i < count; i++) {
            attribution.add(new CostEntry("t1", "d" + i,
                    "aws-us", "r", 1));
        }
        assertThat(attribution.total()).isEqualTo(count);
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedTenants(int count) {
        CostAttribution attribution = new CostAttribution();
        for (int i = 0; i < count; i++) {
            attribution.add(new CostEntry("t" + i, "d",
                    "aws-us", "r", i));
        }
        assertThat(attribution.byTenant()).hasSize(count);
    }

    @Test
    void concurrentAdds() throws Exception {
        CostAttribution attribution = new CostAttribution();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    attribution.add(new CostEntry("t1", "d",
                            "aws-us", "r", 1));
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(attribution.size()).isEqualTo(400);
        assertThat(attribution.total()).isEqualTo(400);
    }

    @Test
    void zeroCostAllowed() {
        CostAttribution attribution = new CostAttribution();
        attribution.add(new CostEntry("t1", "d", "c", "r", 0));
        assertThat(attribution.total()).isZero();
    }

    private static CostAttribution attribution() {
        CostAttribution attribution = new CostAttribution();
        attribution.add(new CostEntry("t1", "orders", "aws-us",
                "storage", 10));
        attribution.add(new CostEntry("t1", "payments", "aws-us",
                "compute", 5));
        attribution.add(new CostEntry("t2", "payments", "gcp-us",
                "storage", 5));
        return attribution;
    }
}
