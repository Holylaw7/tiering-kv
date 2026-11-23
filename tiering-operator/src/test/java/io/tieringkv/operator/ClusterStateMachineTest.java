package io.tieringkv.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 集群状态机（ADR-0322）：转换矩阵 + 控制器集成。 */
class ClusterStateMachineTest {

    @Test
    void happyPathProvisionToReadyToUpgrade() {
        ClusterStateMachine machine =
                new ClusterStateMachine(ClusterPhase.PENDING);
        assertThat(machine.transition(ClusterPhase.PROVISIONING))
                .isEqualTo(ClusterPhase.PROVISIONING);
        assertThat(machine.transition(ClusterPhase.READY))
                .isEqualTo(ClusterPhase.READY);
        assertThat(machine.transition(ClusterPhase.UPGRADING))
                .isEqualTo(ClusterPhase.UPGRADING);
        assertThat(machine.transition(ClusterPhase.READY))
                .isEqualTo(ClusterPhase.READY);
    }

    @Test
    void anyPhaseCanFailAndRetry() {
        for (ClusterPhase phase : ClusterPhase.values()) {
            if (phase == ClusterPhase.PENDING
                    || phase == ClusterPhase.FAILED) {
                continue;
            }
            ClusterStateMachine machine =
                    new ClusterStateMachine(phase);
            machine.transition(ClusterPhase.FAILED);
            assertThat(machine.transition(ClusterPhase.PENDING))
                    .isEqualTo(ClusterPhase.PENDING);
        }
    }

    @ParameterizedTest(name = "phase {0}")
    @ValueSource(strings = {"PENDING", "PROVISIONING", "READY",
            "UPGRADING", "BACKING_UP", "RESTORING", "FAILED"})
    void illegalSkipTransitionRejected(String phaseName) {
        ClusterPhase phase = ClusterPhase.valueOf(phaseName);
        ClusterStateMachine machine =
                new ClusterStateMachine(phase);
        ClusterPhase illegal = phase == ClusterPhase.PENDING
                ? ClusterPhase.UPGRADING : ClusterPhase.PROVISIONING;
        assertThatThrownBy(() -> machine.transition(illegal))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void allowedFromIsStable() {
        assertThat(ClusterStateMachine.allowedFrom(
                ClusterPhase.READY)).contains(
                ClusterPhase.UPGRADING, ClusterPhase.BACKING_UP,
                ClusterPhase.RESTORING, ClusterPhase.FAILED);
        assertThat(ClusterStateMachine.canTransition(
                ClusterPhase.PENDING, ClusterPhase.PROVISIONING))
                .isTrue();
        assertThat(ClusterStateMachine.canTransition(
                ClusterPhase.PENDING, ClusterPhase.READY)).isFalse();
    }

    @Test
    void controllerAdvancesProvisioningToReady() {
        List<OperatorAction> actions = new ArrayList<>();
        TieringKVController controller = new TieringKVController(
                actions::add);
        TieringKVClusterSpec spec = new TieringKVClusterSpec(
                3, 3, List.of("r1", "r2"), "tiering-kv:v1",
                "0 2 * * *", 24);
        // 第一轮：PENDING → PROVISIONING
        controller.applySpec(spec, status(0, 0, 0, 0));
        assertThat(controller.phase())
                .isEqualTo(ClusterPhase.PROVISIONING);
        // 第二轮：全部就绪 → READY
        controller.applySpec(spec, status(3, 3, 1, 1));
        assertThat(controller.phase()).isEqualTo(ClusterPhase.READY);
    }

    @Test
    void controllerUpgradesOnGenerationAdvance() {
        TieringKVController controller = new TieringKVController(
                action -> {
                });
        TieringKVClusterSpec spec = new TieringKVClusterSpec(
                3, 3, List.of("r1"), "tiering-kv:v1",
                "0 2 * * *", 24);
        // 第一轮：PENDING → PROVISIONING
        controller.applySpec(spec, status(3, 3, 1, 1));
        assertThat(controller.phase())
                .isEqualTo(ClusterPhase.PROVISIONING);
        // 第二轮：全部就绪 → READY
        controller.applySpec(spec, status(3, 3, 1, 1));
        assertThat(controller.phase()).isEqualTo(ClusterPhase.READY);
        // 第三轮：generation 前进且未观测 → UPGRADING
        controller.applySpec(spec, status(3, 3, 1, 1));
        assertThat(controller.phase())
                .isEqualTo(ClusterPhase.UPGRADING);
        // 第四轮：观测到新 generation → READY
        controller.applySpec(spec, status(3, 3, 1, 4));
        assertThat(controller.phase()).isEqualTo(ClusterPhase.READY);
    }

    private static TieringKVClusterStatus status(int metadata,
                                                 int storage,
                                                 int gateway,
                                                 long generation) {
        return new TieringKVClusterStatus(metadata, storage, gateway,
                generation, "none");
    }
}
