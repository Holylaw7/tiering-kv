package io.tieringkv.operations.slo;

import io.tieringkv.operations.slo.SloAlert.Alert;
import io.tieringkv.operations.slo.SloManager.SloDefinition;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SLO 告警（ADR-0162）：违约/风险告警。 */
class SloAlertTest {

    @Test
    void compliantNoAlerts() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", true);
        }
        assertThat(new SloAlert().evaluate(manager,
                List.of("s"))).isEmpty();
    }

    @Test
    void breachedAlerts() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", false);
        }
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("s"));
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).status()).isEqualTo("BREACHED");
    }

    @Test
    void atRiskAlerts() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", i < 8);
        }
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("s"));
        assertThat(alerts.get(0).status()).isEqualTo("AT_RISK");
    }

    @Test
    void multipleSloAlerts() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("a", "latency", 0.9, 10));
        manager.define(new SloDefinition("b", "throughput", 0.9,
                10));
        manager.record("a", false);
        manager.record("b", true);
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("a", "b"));
        assertThat(alerts).extracting(Alert::sloId)
                .containsExactly("a");
    }

    @Test
    void alertMessageCarriesCompliance() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        manager.record("s", false);
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("s"));
        assertThat(alerts.get(0).message()).contains("s")
                .contains("0.0");
    }

    @Test
    void nullManagerRejected() {
        assertThatThrownBy(() -> new SloAlert().evaluate(null,
                List.of("s")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullSloIdsRejected() {
        assertThatThrownBy(() -> new SloAlert().evaluate(
                new SloManager(), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptySloIdsNoAlerts() {
        assertThat(new SloAlert().evaluate(new SloManager(),
                List.of())).isEmpty();
    }

    @ParameterizedTest(name = "success {0}")
    @ValueSource(ints = {0, 5, 8, 9, 10})
    void parameterizedSloStatus(int success) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("s", "latency", 0.9, 10));
        for (int i = 0; i < 10; i++) {
            manager.record("s", i < success);
        }
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("s"));
        if (success >= 9) {
            assertThat(alerts).isEmpty();
        } else if (success == 8) {
            assertThat(alerts.get(0).status()).isEqualTo("AT_RISK");
        } else {
            assertThat(alerts.get(0).status()).isEqualTo("BREACHED");
        }
    }

    @ParameterizedTest(name = "slo {0}")
    @ValueSource(strings = {"a", "b", "c"})
    void parameterizedSloNames(String sloId) {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition(sloId, "latency", 0.9,
                10));
        manager.record(sloId, false);
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of(sloId));
        assertThat(alerts.get(0).sloId()).isEqualTo(sloId);
    }

    @Test
    void alertOrderFollowsInput() {
        SloManager manager = new SloManager();
        manager.define(new SloDefinition("a", "latency", 0.9, 1));
        manager.define(new SloDefinition("b", "latency", 0.9, 1));
        manager.record("a", false);
        manager.record("b", false);
        List<Alert> alerts = new SloAlert().evaluate(manager,
                List.of("a", "b"));
        assertThat(alerts).extracting(Alert::sloId)
                .containsExactly("a", "b");
    }
}
