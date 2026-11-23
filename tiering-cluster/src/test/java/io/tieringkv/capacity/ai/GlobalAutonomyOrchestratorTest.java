package io.tieringkv.capacity.ai;

import io.tieringkv.capacity.ai.AutoCapacityAdvisor.Advice;
import io.tieringkv.capacity.ai.AutoCapacityAdvisor.RiskLevel;
import io.tieringkv.capacity.ai.GlobalAutonomyOrchestrator.ActionResult;
import io.tieringkv.capacity.ai.GlobalAutonomyOrchestrator.Outcome;
import io.tieringkv.capacity.ai.GlobalAutonomyOrchestrator.Policy;
import io.tieringkv.gateway.AutonomousTrafficController;
import io.tieringkv.gateway.RegionQuota;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 全球受限自治（ADR-0157）：围栏矩阵 + 回滚 + 失败登记。 */
class GlobalAutonomyOrchestratorTest {

    @Test
    void capacityExecutedWithinBudget() {
        Fixture fixture = fixture();
        ActionResult result = fixture.orchestrator().applyCapacity(
                advice(4));
        assertThat(result.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(fixture.orchestrator().actionsToday()).isEqualTo(1);
    }

    @Test
    void capacityRejectedOverDailyBudget() {
        Fixture fixture = fixture(new Policy(1, 5, true));
        fixture.orchestrator().applyCapacity(advice(4));
        ActionResult result = fixture.orchestrator().applyCapacity(
                advice(6));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reason()).contains("budget");
    }

    @Test
    void capacityRejectedWhenCircuitOpen() {
        Fixture fixture = fixture();
        fixture.orchestrator().openCircuit("global failure");
        ActionResult result = fixture.orchestrator().applyCapacity(
                advice(4));
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reason()).contains("circuit open");
    }

    @Test
    void trafficExecuted() {
        Fixture fixture = fixture();
        ActionResult result = fixture.orchestrator().applyTraffic(
                "r1", 80);
        assertThat(result.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(fixture.orchestrator().affectedRegions())
                .containsExactly("r1");
    }

    @Test
    void trafficRegionCapExceeded() {
        Fixture fixture = fixture(new Policy(10, 1, true));
        fixture.orchestrator().applyTraffic("r1", 80);
        ActionResult result = fixture.orchestrator().applyTraffic(
                "r2", 80);
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reason()).contains("region cap");
    }

    @Test
    void reshardExecuted() {
        Fixture fixture = fixture();
        ActionResult result = fixture.orchestrator().applyReshard(
                "plan-1");
        assertThat(result.outcome()).isEqualTo(Outcome.EXECUTED);
        assertThat(fixture.reshardCalled().get()).isTrue();
    }

    @Test
    void reshardDisabledByPolicy() {
        Fixture fixture = fixture(new Policy(10, 5, false));
        ActionResult result = fixture.orchestrator().applyReshard(
                "plan-1");
        assertThat(result.outcome()).isEqualTo(Outcome.REJECTED);
        assertThat(result.reason()).contains("disabled");
    }

    @Test
    void reshardExecutorFalseRolledBack() {
        Fixture fixture = fixture(planId -> false);
        ActionResult result = fixture.orchestrator().applyReshard(
                "plan-1");
        assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
    }

    @Test
    void reshardExecutorExceptionRolledBack() {
        Fixture fixture = fixture(planId -> {
            throw new IllegalStateException("migration failed");
        });
        ActionResult result = fixture.orchestrator().applyReshard(
                "plan-1");
        assertThat(result.outcome()).isEqualTo(Outcome.ROLLED_BACK);
        assertThat(fixture.orchestrator().failures()).isNotEmpty();
    }

    @Test
    void rollbackRestoresTrafficAndCapacity() {
        Fixture fixture = fixture();
        fixture.orchestrator().applyCapacity(advice(4));
        fixture.orchestrator().applyTraffic("r1", 80);
        fixture.orchestrator().rollback();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
        assertThat(fixture.capacity().currentNodes()).isEqualTo(2);
    }

    @Test
    void newDayResetsBudgetAndRegions() {
        Fixture fixture = fixture(new Policy(1, 5, true));
        fixture.orchestrator().applyTraffic("r1", 80);
        fixture.orchestrator().newDay();
        assertThat(fixture.orchestrator().actionsToday()).isZero();
        assertThat(fixture.orchestrator().affectedRegions()).isEmpty();
        assertThat(fixture.orchestrator().applyTraffic("r2", 80)
                .outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void failuresRecordedOnRejection() {
        Fixture fixture = fixture(new Policy(1, 1, true));
        fixture.orchestrator().applyCapacity(advice(4));
        fixture.orchestrator().applyCapacity(advice(6));
        assertThat(fixture.orchestrator().failures()).isNotEmpty();
    }

    @Test
    void invalidPolicyRejected() {
        assertThatThrownBy(() -> new Policy(0, 5, true))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new Policy(5, 0, true))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void circuitResetRestoresExecution() {
        Fixture fixture = fixture();
        fixture.orchestrator().openCircuit("failure");
        fixture.orchestrator().resetCircuit();
        assertThat(fixture.orchestrator().applyTraffic("r1", 80)
                .outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void affectedRegionsTracked() {
        Fixture fixture = fixture(new Policy(10, 3, true));
        fixture.orchestrator().applyTraffic("r1", 80);
        fixture.orchestrator().applyTraffic("r2", 80);
        assertThat(fixture.orchestrator().affectedRegions())
                .containsExactlyInAnyOrder("r1", "r2");
    }

    @ParameterizedTest(name = "budget {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedDailyBudgets(int budget) {
        Fixture fixture = fixture(new Policy(budget, 50, true));
        for (int i = 0; i < budget; i++) {
            assertThat(fixture.orchestrator().applyTraffic(
                    "r" + i, 80).outcome())
                    .isEqualTo(Outcome.EXECUTED);
        }
        assertThat(fixture.orchestrator().applyTraffic("r99", 80)
                .outcome()).isEqualTo(Outcome.REJECTED);
    }

    @ParameterizedTest(name = "region cap {0}")
    @ValueSource(ints = {1, 3, 10})
    void parameterizedRegionCaps(int cap) {
        Fixture fixture = fixture(new Policy(100, cap, true));
        for (int i = 0; i < cap; i++) {
            assertThat(fixture.orchestrator().applyTraffic(
                    "r" + i, 80).outcome())
                    .isEqualTo(Outcome.EXECUTED);
        }
        assertThat(fixture.orchestrator().applyTraffic("r99", 80)
                .outcome()).isEqualTo(Outcome.REJECTED);
    }

    @ParameterizedTest(name = "action {0}")
    @ValueSource(strings = {"capacity", "traffic", "reshard"})
    void parameterizedActions(String action) {
        Fixture fixture = fixture(new Policy(10, 5, true));
        ActionResult result = switch (action) {
            case "capacity" -> fixture.orchestrator().applyCapacity(
                    advice(4));
            case "traffic" -> fixture.orchestrator().applyTraffic(
                    "r1", 80);
            default -> fixture.orchestrator().applyReshard("plan");
        };
        assertThat(result.outcome()).isEqualTo(Outcome.EXECUTED);
    }

    @Test
    void concurrentOrchestrationSerialized() throws Exception {
        Fixture fixture = fixture(new Policy(1000, 10, true));
        Thread[] threads = new Thread[4];
        for (int t = 0; t < threads.length; t++) {
            threads[t] = new Thread(() -> {
                for (int i = 0; i < 50; i++) {
                    fixture.orchestrator().applyTraffic(
                            "r" + (i % 5), 80);
                }
            });
            threads[t].start();
        }
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(fixture.orchestrator().actionsToday())
                .isEqualTo(200);
    }

    @Test
    void rollbackIsIdempotent() {
        Fixture fixture = fixture();
        fixture.orchestrator().applyTraffic("r1", 80);
        fixture.orchestrator().rollback();
        fixture.orchestrator().rollback();
        assertThat(fixture.quota().quota("r1")).isEqualTo(50);
    }

    @Test
    void restoreDoesNotConsumeBudget() {
        Fixture fixture = fixture(new Policy(1, 5, true));
        fixture.orchestrator().applyCapacity(advice(4));
        fixture.capacity().restore(2);
        assertThat(fixture.orchestrator().actionsToday()).isEqualTo(1);
    }

    private static Fixture fixture() {
        return fixture(new Policy(10, 5, true));
    }

    private static Fixture fixture(Policy policy) {
        return fixture(policy, planId -> true);
    }

    private static Fixture fixture(
            java.util.function.Function<String, Boolean> reshard) {
        return fixture(new Policy(10, 5, true), reshard);
    }

    private static Fixture fixture(
            Policy policy,
            java.util.function.Function<String, Boolean> reshard) {
        RegionQuota quota = new RegionQuota();
        quota.setQuota("r1", 50);
        quota.setQuota("r2", 50);
        AutonomousTrafficController traffic =
                new AutonomousTrafficController(quota, 0.5, 10, 200);
        AutonomousCapacityController capacity =
                new AutonomousCapacityController(2, 5, 100, 100);
        AtomicBoolean called = new AtomicBoolean();
        GlobalAutonomyOrchestrator orchestrator =
                new GlobalAutonomyOrchestrator(capacity, traffic,
                        policy, planId -> {
                            called.set(true);
                            return reshard.apply(planId);
                        });
        return new Fixture(quota, capacity, orchestrator, called);
    }

    private record Fixture(RegionQuota quota,
                           AutonomousCapacityController capacity,
                           GlobalAutonomyOrchestrator orchestrator,
                           AtomicBoolean reshardCalled) {
    }

    private static Advice advice(int nodes) {
        return new Advice("qps", 100, 200, nodes, 2,
                RiskLevel.LOW, 0.9);
    }
}
