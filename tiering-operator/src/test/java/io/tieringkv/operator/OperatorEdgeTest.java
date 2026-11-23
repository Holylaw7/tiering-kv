package io.tieringkv.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Operator 边缘矩阵（ADR-0107）：副本组合、收敛、无备份升级。 */
class OperatorEdgeTest {

    @ParameterizedTest(name = "metadata {0} storage {1}")
    @ValueSource(ints = {1, 3, 5})
    void parameterizedReplicaCombos(int metadata) {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(metadata, 3,
                List.of("r1"), "v1", null, 168);
        TieringKVClusterStatus current = new TieringKVClusterStatus(
                metadata, 3, 1, 1, "none");
        List<OperatorAction> actions = planner.plan(spec, current, 1);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type())
                .isEqualTo(OperatorAction.ActionType.NOOP);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {1, 3, 6})
    void parameterizedStorageCombos(int storage) {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(3, storage,
                List.of("r1"), "v1", null, 168);
        TieringKVClusterStatus current = new TieringKVClusterStatus(
                3, storage, 1, 1, "none");
        List<OperatorAction> actions = planner.plan(spec, current, 1);
        assertThat(actions.get(0).type())
                .isEqualTo(OperatorAction.ActionType.NOOP);
    }

    @Test
    void upgradeWithoutBackupSchedule() {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(3, 3,
                List.of("r1"), "v2", null, 168);
        TieringKVClusterStatus current = new TieringKVClusterStatus(
                3, 3, 1, 1, "none");
        List<OperatorAction> actions = planner.plan(spec, current, 2);
        assertThat(actions).extracting(OperatorAction::type)
                .doesNotContain(OperatorAction.ActionType.TRIGGER_BACKUP);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.UPGRADE);
    }
}
