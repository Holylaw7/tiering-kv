package io.tieringkv.saas.operations;

import io.tieringkv.saas.billing.BillingPeriod;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.UsageMeter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** MRR 计算（ADR-0155）：周期收入聚合。 */
class MrrCalculatorTest {

    @Test
    void mrrFromActiveTenants() {
        MrrCalculator calculator = new MrrCalculator();
        calculator.setMonthlyAmount("t1", 100);
        calculator.setMonthlyAmount("t2", 50);
        calculator.setMonthlyAmount("t3", 25);
        assertThat(calculator.mrr(Set.of("t1", "t2"))).isEqualTo(150);
        assertThat(calculator.total()).isEqualTo(175);
    }

    @Test
    void recordInvoiceSetsAmount() {
        MrrCalculator calculator = new MrrCalculator();
        calculator.record(invoice("t1", 42));
        assertThat(calculator.amount("t1")).isEqualTo(42);
    }

    @Test
    void recordInvoiceOverwrites() {
        MrrCalculator calculator = new MrrCalculator();
        calculator.record(invoice("t1", 10));
        calculator.record(invoice("t1", 20));
        assertThat(calculator.amount("t1")).isEqualTo(20);
    }

    @Test
    void emptyMrrZero() {
        assertThat(new MrrCalculator().mrr(Set.of())).isZero();
        assertThat(new MrrCalculator().total()).isZero();
    }

    @Test
    void unknownTenantAmountZero() {
        assertThat(new MrrCalculator().amount("missing")).isZero();
    }

    @Test
    void byTenantSnapshot() {
        MrrCalculator calculator = new MrrCalculator();
        calculator.setMonthlyAmount("t1", 10);
        calculator.setMonthlyAmount("t2", 20);
        assertThat(calculator.byTenant())
                .containsEntry("t1", 10.0)
                .containsEntry("t2", 20.0);
    }

    @Test
    void negativeAmountRejected() {
        assertThatThrownBy(() -> new MrrCalculator()
                .setMonthlyAmount("t1", -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new MrrCalculator()
                .setMonthlyAmount("", 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullInvoiceRejected() {
        assertThatThrownBy(() -> new MrrCalculator().record(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "amounts {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedTenantCounts(int count) {
        MrrCalculator calculator = new MrrCalculator();
        for (int i = 0; i < count; i++) {
            calculator.setMonthlyAmount("t" + i, i);
        }
        double expected = count * (count - 1) / 2.0;
        assertThat(calculator.total()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(doubles = {0.0, 9.99, 100.0})
    void parameterizedAmounts(double value) {
        MrrCalculator calculator = new MrrCalculator();
        calculator.setMonthlyAmount("t1", value);
        assertThat(calculator.amount("t1")).isEqualTo(value);
    }

    @Test
    void concurrentRecords() throws Exception {
        MrrCalculator calculator = new MrrCalculator();
        Thread[] threads = new Thread[8];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 100; i++) {
                    calculator.setMonthlyAmount("t" + i, 1);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(calculator.byTenant()).hasSize(100);
    }

    private static Invoice invoice(String tenantId, double total) {
        return new Invoice(tenantId, "p1",
                new BillingPeriod(0, 1, true),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.REQUESTS, 1, total,
                        total)));
    }
}
