package io.tieringkv.saas.operations;

import io.tieringkv.saas.operations.CommercialAlert.Alert;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 商业化告警（ADR-0155）：阈值矩阵。 */
class CommercialAlertTest {

    @Test
    void healthyNoAlerts() {
        assertThat(new CommercialAlert().evaluate(0.01, 0.8,
                100, 100, 0.05, 0.3, 0.1)).isEmpty();
    }

    @Test
    void churnAboveThresholdAlerts() {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.1, 0.8, 100, 100, 0.05, 0.3, 0.1);
        assertThat(alerts).extracting(Alert::type)
                .containsExactly("CHURN");
    }

    @Test
    void conversionBelowThresholdAlerts() {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.01, 0.1, 100, 100, 0.05, 0.3, 0.1);
        assertThat(alerts).extracting(Alert::type)
                .containsExactly("CONVERSION");
    }

    @Test
    void mrrDropAlerts() {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.01, 0.8, 80, 100, 0.05, 0.3, 0.1);
        assertThat(alerts).extracting(Alert::type)
                .containsExactly("MRR_DROP");
    }

    @Test
    void multipleAlertsTogether() {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.1, 0.1, 50, 100, 0.05, 0.3, 0.1);
        assertThat(alerts).extracting(Alert::type)
                .containsExactlyInAnyOrder(
                        "CHURN", "CONVERSION", "MRR_DROP");
    }

    @Test
    void mrrGrowthNoAlert() {
        assertThat(new CommercialAlert().evaluate(0.01, 0.8,
                120, 100, 0.05, 0.3, 0.1)).isEmpty();
    }

    @Test
    void zeroBaselineNoDropAlert() {
        assertThat(new CommercialAlert().evaluate(0.01, 0.8,
                10, 0, 0.05, 0.3, 0.1)).isEmpty();
    }

    @Test
    void alertMessageCarriesValues() {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.2, 0.8, 100, 100, 0.05, 0.3, 0.1);
        assertThat(alerts.get(0).message()).contains("0.2")
                .contains("0.05");
    }

    @Test
    void convenienceOverloadDefaultThresholds() {
        ChurnDetector churn = new ChurnDetector();
        churn.recordChurn();
        churn.recordRenewal();
        TrialConversionTracker conversion =
                new TrialConversionTracker();
        conversion.startTrial();
        conversion.markExpired();
        List<Alert> alerts = new CommercialAlert().evaluate(
                churn, conversion, 80, 100);
        assertThat(alerts).extracting(Alert::type)
                .containsExactlyInAnyOrder(
                        "CHURN", "CONVERSION", "MRR_DROP");
    }

    @Test
    void convenienceOverloadHealthy() {
        ChurnDetector churn = new ChurnDetector();
        churn.recordRenewal();
        TrialConversionTracker conversion =
                new TrialConversionTracker();
        conversion.startTrial();
        conversion.markConverted();
        assertThat(new CommercialAlert().evaluate(churn,
                conversion, 100, 100)).isEmpty();
    }

    @ParameterizedTest(name = "churn {0} threshold {1}")
    @CsvSource({"0.01,0.05,false", "0.06,0.05,true",
            "0.05,0.05,false"})
    void parameterizedChurnThresholds(double churnRate,
                                      double threshold,
                                      boolean fired) {
        List<Alert> alerts = new CommercialAlert().evaluate(
                churnRate, 0.8, 100, 100, threshold, 0.3, 0.1);
        assertThat(alerts.stream().anyMatch(
                alert -> alert.type().equals("CHURN")))
                .isEqualTo(fired);
    }

    @ParameterizedTest(name = "conversion {0} threshold {1}")
    @CsvSource({"0.8,0.3,false", "0.2,0.3,true",
            "0.3,0.3,false"})
    void parameterizedConversionThresholds(double conversionRate,
                                           double threshold,
                                           boolean fired) {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.01, conversionRate, 100, 100, 0.05,
                threshold, 0.1);
        assertThat(alerts.stream().anyMatch(
                alert -> alert.type().equals("CONVERSION")))
                .isEqualTo(fired);
    }

    @ParameterizedTest(name = "mrr {0} baseline {1}")
    @CsvSource({"90,100,false", "80,100,true", "89,100,true"})
    void parameterizedMrrDrop(double mrrNow, double mrrBefore,
                              boolean fired) {
        List<Alert> alerts = new CommercialAlert().evaluate(
                0.01, 0.8, mrrNow, mrrBefore, 0.05, 0.3, 0.1);
        assertThat(alerts.stream().anyMatch(
                alert -> alert.type().equals("MRR_DROP")))
                .isEqualTo(fired);
    }
}
