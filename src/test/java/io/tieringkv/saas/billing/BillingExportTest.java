package io.tieringkv.saas.billing;

import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.MeteredBilling;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 账单导出与周期结算（ADR-0130）：行项目、CSV/JSON。 */
class BillingExportTest {

    private static Invoice invoice() {
        return new Invoice("t1", "basic",
                new BillingPeriod(0, 1000, true),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.REQUESTS, 100, 0.01, 1.0),
                        new Invoice.LineItem(
                                UsageMeter.MeterType.STORAGE_GB,
                                10, 5.0, 50.0)));
    }

    @Test
    void invoiceTotal() {
        assertThat(invoice().total()).isEqualTo(51.0);
    }

    @Test
    void periodFrozen() {
        BillingPeriod period = new BillingPeriod(0, 1000, true);
        assertThat(period.frozen()).isTrue();
        assertThat(period.endMillis()).isEqualTo(1000);
    }

    @Test
    void csvExportHasHeaderAndRows() {
        String csv = new InvoiceExporter().toCsv(invoice());
        assertThat(csv).startsWith("tenant,plan,type,quantity,"
                + "unitPrice,subtotal\n");
        assertThat(csv).contains("t1,basic,REQUESTS,100,0.01,1.0");
        assertThat(csv).contains("t1,basic,STORAGE_GB,10,5.0,50.0");
    }

    @Test
    void jsonExportContainsTotal() {
        String json = new InvoiceExporter().toJson(invoice());
        assertThat(json).contains("\"tenant\":\"t1\"");
        assertThat(json).contains("\"total\":51.0");
        assertThat(json).contains("\"lineItems\"");
    }

    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(longs = {0, 1, 1000})
    void parameterizedLineItems(long quantity) {
        Invoice invoice = new Invoice("t1", "p",
                new BillingPeriod(0, 1, true),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.EGRESS_GB,
                        quantity, 0.5, quantity * 0.5)));
        assertThat(invoice.total()).isEqualTo(quantity * 0.5);
    }

    @Test
    void settlementFromMeter() {
        UsageMeter meter = new UsageMeter();
        meter.record(UsageMeter.MeterType.REQUESTS, 100);
        BillingPlan plan = new BillingPlan("basic", Map.of(
                UsageMeter.MeterType.REQUESTS, 0.01));
        double charge = new MeteredBilling().calculate(meter, plan);
        Invoice invoice = new Invoice("t1", "basic",
                new BillingPeriod(0, 1000, true),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.REQUESTS, 100,
                        0.01, charge)));
        assertThat(invoice.total()).isEqualTo(1.0);
    }

    @Test
    void emptyInvoiceTotalZero() {
        Invoice invoice = new Invoice("t1", "p",
                new BillingPeriod(0, 1, false), List.of());
        assertThat(invoice.total()).isZero();
        assertThat(new InvoiceExporter().toCsv(invoice))
                .contains("tenant,plan");
    }

    @ParameterizedTest(name = "price {0}")
    @ValueSource(doubles = {0.1, 2.5})
    void parameterizedInvoiceSubtotal(double price) {
        Invoice invoice = new Invoice("t1", "p",
                new BillingPeriod(0, 1, false),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.STORAGE_GB, 4,
                        price, 4 * price)));
        assertThat(invoice.total()).isEqualTo(4 * price);
    }
}
