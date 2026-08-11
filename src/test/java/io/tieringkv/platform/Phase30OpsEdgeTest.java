package io.tieringkv.platform;

import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.saas.billing.BillingPeriod;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.billing.InvoiceExporter;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.sql.SqlEngine;
import io.tieringkv.sql.distributed.PredicatePushdown;
import io.tieringkv.sql.distributed.QueryCache;
import io.tieringkv.sql.distributed.ShardPlanner;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 30 运维边缘：水位、账单、容量、缓存参数矩阵。 */
class Phase30OpsEdgeTest {

    @ParameterizedTest(name = "lag {0}")
    @ValueSource(longs = {0, 10, 1000})
    void watermarkLagMatrix(long lag) {
        AtomicLong replicated = new AtomicLong(1000);
        GlobalReadRouter router = new GlobalReadRouter(
                replicated::get, region -> 1000 - lag,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 1000)).isEqualTo("a");
    }

    @ParameterizedTest(name = "staleness {0}")
    @ValueSource(longs = {1, 50, 999})
    void stalenessSampleBoundaries(long staleness) {
        GlobalReadRouter router = new GlobalReadRouter(
                Map.of(), region -> 0L, ConsistencyMode.BOUNDED);
        router.recordStaleness(staleness);
        assertThat(router.stalenessMillis()).isEqualTo(staleness);
    }

    @ParameterizedTest(name = "items {0}")
    @ValueSource(ints = {0, 1, 5})
    void invoiceLineItems(int count) {
        List<Invoice.LineItem> items = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            items.add(new Invoice.LineItem(
                    UsageMeter.MeterType.REQUESTS, i, 0.1, i * 0.1));
        }
        Invoice invoice = new Invoice("t", "p",
                new BillingPeriod(0, 1, true), items);
        assertThat(invoice.lineItems()).hasSize(count);
        assertThat(new InvoiceExporter().toJson(invoice))
                .contains("lineItems");
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(longs = {0, 500, 5000})
    void capacityStorageBoundaries(long storageGB) {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(4, storageGB, 10_000,
                        100, 100_000);
        assertThat(estimate.nodes()).isGreaterThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "plans {0}")
    @ValueSource(ints = {2, 16})
    void predicatePushdownPlans(int shards) {
        List<io.tieringkv.sql.distributed.ShardPlan> plans =
                new ShardPlanner().plan(List.of("r1"), shards, "k");
        assertThat(new PredicatePushdown().filter(plans,
                bytes("k000001"), bytes("k000002"))).hasSize(1);
    }

    @ParameterizedTest(name = "queries {0}")
    @ValueSource(ints = {1, 10})
    void queryCacheQueries(int count) {
        QueryCache cache = new QueryCache();
        for (int i = 0; i < count; i++) {
            cache.put("q" + i, i, List.of(
                    new SqlEngine.Row(bytes("k"), bytes("v"))));
        }
        assertThat(cache.size()).isEqualTo(count);
    }

    @Test
    void queryCacheWatermarkInvalidation() {
        QueryCache cache = new QueryCache();
        cache.put("q", 100, List.of(
                new SqlEngine.Row(bytes("k"), bytes("v"))));
        assertThat(cache.get("q", 100)).isNotNull();
        assertThat(cache.get("q", 101)).isNull();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
