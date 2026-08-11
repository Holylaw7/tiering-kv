package io.tieringkv.saas;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** SaaS 计费与市场（ADR-0124）。 */
class SaasMeteringTest {

    @Test
    void usageMeterRecordsAndSnapshots() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 100);
        meter.record(UsageMeter.MeterType.REQUESTS, 50);
        meter.record(UsageMeter.MeterType.STORAGE_GB, 10);
        assertThat(meter.get(UsageMeter.MeterType.REQUESTS))
                .isEqualTo(150);
        assertThat(meter.snapshot())
                .containsEntry(UsageMeter.MeterType.REQUESTS, 150L)
                .containsEntry(UsageMeter.MeterType.STORAGE_GB, 10L);
    }

    @Test
    void usageMeterMissingZero() {
        UsageMeter meter = new UsageMeter();
        assertThat(meter.get(UsageMeter.MeterType.EGRESS_GB)).isZero();
    }

    @Test
    void usageMeterReset() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 10);
        meter.reset();
        assertThat(meter.get(UsageMeter.MeterType.REQUESTS)).isZero();
    }

    @ParameterizedTest(name = "requests {0}")
    @ValueSource(longs = {0, 100, 1_000_000})
    void parameterizedRequestMetering(long requests) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, requests);
        assertThat(meter.get(UsageMeter.MeterType.REQUESTS))
                .isEqualTo(requests);
    }

    @Test
    void meteredBillingCalculates() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 1_000);
        meter.record(UsageMeter.MeterType.STORAGE_GB, 10);
        BillingPlan plan = new BillingPlan("basic", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.001,
                UsageMeter.MeterType.STORAGE_GB, 5.0));
        assertThat(new MeteredBilling().calculate(meter, plan))
                .isEqualTo(1.0 + 50.0);
    }

    @Test
    void meteredBillingUnpricedZero() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 100);
        BillingPlan plan = new BillingPlan("free", Map.of());
        assertThat(new MeteredBilling().calculate(meter, plan)).isZero();
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.0, 1.5, 100.0})
    void parameterizedBillingPrice(double price) {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.EGRESS_GB, 2);
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.EGRESS_GB, price));
        assertThat(new MeteredBilling().calculate(meter, plan))
                .isEqualTo(2 * price);
    }

    @Test
    void clusterTemplateDefinesPricing() {
        ClusterTemplate template = new ClusterTemplate("small", 3, 3,
                100, 99.0);
        assertThat(template.metadataReplicas()).isEqualTo(3);
        assertThat(template.monthlyPrice()).isEqualTo(99.0);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {10, 100, 1000})
    void parameterizedTemplates(int storage) {
        ClusterTemplate template = new ClusterTemplate("t", 3, 3,
                storage, storage * 1.5);
        assertThat(template.maxStorageGB()).isEqualTo(storage);
        assertThat(template.monthlyPrice()).isEqualTo(storage * 1.5);
    }

    @Test
    void quotaDegradationPrototype() {
        ClusterTenant tenant = new ClusterTenant("t1", "prod", 3, 100);
        TenantQuotaValidator validator = new TenantQuotaValidator();
        assertThat(validator.validateAll(tenant, 4, 50)).hasSize(1);
    }
}
