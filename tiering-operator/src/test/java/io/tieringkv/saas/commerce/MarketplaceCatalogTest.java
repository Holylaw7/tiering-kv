package io.tieringkv.saas.commerce;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.ClusterTemplate;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 市场目录（ADR-0146）：模板 + 计划注册与查询。 */
class MarketplaceCatalogTest {

    @Test
    void registerAndQueryTemplate() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        ClusterTemplate template = new ClusterTemplate("s", 3, 3,
                100, 99.0);
        catalog.registerTemplate(template);
        assertThat(catalog.template("s")).contains(template);
        assertThat(catalog.templateIds()).containsExactly("s");
    }

    @Test
    void registerAndQueryPlan() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01));
        catalog.registerPlan(plan);
        assertThat(catalog.plan("p")).contains(plan);
        assertThat(catalog.planIds()).containsExactly("p");
    }

    @Test
    void missingTemplateEmpty() {
        assertThat(new MarketplaceCatalog().template("missing"))
                .isEmpty();
    }

    @Test
    void missingPlanEmpty() {
        assertThat(new MarketplaceCatalog().plan("missing"))
                .isEmpty();
    }

    @Test
    void duplicateTemplateOverwrites() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerTemplate(new ClusterTemplate("s", 3, 3,
                100, 99.0));
        catalog.registerTemplate(new ClusterTemplate("s", 5, 5,
                200, 199.0));
        assertThat(catalog.templateCount()).isEqualTo(1);
        assertThat(catalog.template("s").orElseThrow().maxStorageGB())
                .isEqualTo(200);
    }

    @Test
    void duplicatePlanOverwrites() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        catalog.registerPlan(new BillingPlan("p", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01)));
        catalog.registerPlan(new BillingPlan("p", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.02)));
        assertThat(catalog.planCount()).isEqualTo(1);
        assertThat(catalog.plan("p").orElseThrow().prices()
                .get(UsageMeter.MeterType.REQUESTS)).isEqualTo(0.02);
    }

    @Test
    void nullTemplateRejected() {
        assertThatThrownBy(() -> new MarketplaceCatalog()
                .registerTemplate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullPlanRejected() {
        assertThatThrownBy(() -> new MarketplaceCatalog()
                .registerPlan(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyCatalogCounts() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        assertThat(catalog.templateCount()).isZero();
        assertThat(catalog.planCount()).isZero();
        assertThat(catalog.templateIds()).isEmpty();
        assertThat(catalog.planIds()).isEmpty();
    }

    @Test
    void multipleRegistrations() {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        for (int i = 0; i < 10; i++) {
            catalog.registerTemplate(new ClusterTemplate("t" + i,
                    3, 3, 100 + i, 10 + i));
            catalog.registerPlan(new BillingPlan("p" + i, Map.of(
                    UsageMeter.MeterType.REQUESTS, 0.001 * i)));
        }
        assertThat(catalog.templateCount()).isEqualTo(10);
        assertThat(catalog.planCount()).isEqualTo(10);
        assertThat(catalog.templateIds()).hasSize(10);
    }

    @Test
    void planPricesAreCopied() {
        Map<UsageMeter.MeterType, Double> prices =
                new java.util.HashMap<>();
        prices.put(UsageMeter.MeterType.REQUESTS, 0.01);
        BillingPlan plan = new BillingPlan("p", prices);
        prices.put(UsageMeter.MeterType.REQUESTS, 99.0);
        assertThat(plan.prices().get(UsageMeter.MeterType.REQUESTS))
                .isEqualTo(0.01);
    }

    @Test
    void catalogIsThreadSafe() throws Exception {
        MarketplaceCatalog catalog = new MarketplaceCatalog();
        Thread writer = new Thread(() -> {
            for (int i = 0; i < 100; i++) {
                catalog.registerTemplate(new ClusterTemplate("t" + i,
                        3, 3, 100, 10));
            }
        });
        Thread reader = new Thread(() -> {
            for (int i = 0; i < 1000; i++) {
                catalog.template("t" + (i % 100));
                catalog.plan("p" + (i % 100));
            }
        });
        writer.start();
        reader.start();
        writer.join(10_000);
        reader.join(10_000);
        assertThat(catalog.templateCount()).isEqualTo(100);
    }
}
