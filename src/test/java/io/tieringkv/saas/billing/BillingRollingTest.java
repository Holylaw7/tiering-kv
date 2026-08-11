package io.tieringkv.saas.billing;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 账单滚动结算（ADR-0136）：周期滚动、冻结、审计。 */
class BillingRollingTest {

    @Test
    void rollGeneratesInvoiceAndResets() {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(1_000, audit);
        scheduler.meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 100);
        BillingPlan plan = new BillingPlan("basic", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01));
        Invoice invoice = scheduler.roll("t1", plan, 0);
        assertThat(invoice.total()).isEqualTo(1.0);
        assertThat(scheduler.meter("t1").get(
                UsageMeter.MeterType.REQUESTS)).isZero();
        assertThat(audit.entries("t1")).hasSize(1);
    }

    @Test
    void nextCycleBillsNewUsage() {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(1_000, audit);
        BillingPlan plan = new BillingPlan("basic", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01));
        scheduler.meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 50);
        scheduler.roll("t1", plan, 0);
        scheduler.meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 30);
        Invoice second = scheduler.roll("t1", plan, 1);
        assertThat(second.total()).isEqualTo(0.3);
        assertThat(second.period().startMillis()).isEqualTo(1_000);
    }

    @ParameterizedTest(name = "period {0}")
    @ValueSource(longs = {1, 100, 1000})
    void parameterizedPeriods(long periodMillis) {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler =
                new BillingScheduler(periodMillis, audit);
        BillingPlan plan = new BillingPlan("p", Map.of(
                UsageMeter.MeterType.STORAGE_GB, 2.0));
        scheduler.meter("t1").record(
                UsageMeter.MeterType.STORAGE_GB, 3);
        Invoice invoice = scheduler.roll("t1", plan, 2);
        assertThat(invoice.period().startMillis())
                .isEqualTo(2 * periodMillis);
        assertThat(invoice.total()).isEqualTo(6.0);
    }

    @ParameterizedTest(name = "cycle {0}")
    @ValueSource(longs = {0, 1, 10})
    void parameterizedCycles(long cycle) {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(100, audit);
        BillingPlan plan = new BillingPlan("p", Map.of());
        scheduler.meter("t1").record(
                UsageMeter.MeterType.REQUESTS, 5);
        Invoice invoice = scheduler.roll("t1", plan, cycle);
        assertThat(invoice.period().startMillis())
                .isEqualTo(cycle * 100);
        assertThat(invoice.total()).isZero();
    }

    @Test
    void unpricedUsageZeroBill() {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(100, audit);
        scheduler.meter("t1").record(
                UsageMeter.MeterType.EGRESS_GB, 10);
        Invoice invoice = scheduler.roll("t1",
                new BillingPlan("free", Map.of()), 0);
        assertThat(invoice.total()).isZero();
    }

    @Test
    void auditTracksEachRoll() {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(100, audit);
        BillingPlan plan = new BillingPlan("p", Map.of());
        scheduler.roll("t1", plan, 0);
        scheduler.roll("t1", plan, 1);
        assertThat(audit.entries("t1")).hasSize(2);
    }
}
