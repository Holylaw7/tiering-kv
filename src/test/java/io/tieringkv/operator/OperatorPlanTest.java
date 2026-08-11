package io.tieringkv.operator;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Operator 计划引擎（ADR-0107）：create/scale/replace/upgrade/backup。 */
class OperatorPlanTest {

    private static TieringKVClusterSpec spec(int metadata, int storage,
                                             String image) {
        return new TieringKVClusterSpec(metadata, storage,
                List.of("r1", "r2"), image, null, 168);
    }

    private static TieringKVClusterStatus status(int metadata, int storage,
                                                 int gateway,
                                                 long generation) {
        return new TieringKVClusterStatus(metadata, storage, gateway,
                generation, "none");
    }

    private static TieringKVClusterStatus status(int metadata, int storage,
                                                 long generation) {
        return status(metadata, storage,
                metadata == 0 && storage == 0 ? 0 : 1, generation);
    }

    @Test
    void emptyClusterCreates() {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v1"),
                status(0, 0, 0), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.CREATE);
    }

    @Test
    void convergedClusterNoop() {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v1"),
                status(3, 3, 1), 1);
        assertThat(actions).hasSize(1);
        assertThat(actions.get(0).type())
                .isEqualTo(OperatorAction.ActionType.NOOP);
    }

    @ParameterizedTest(name = "metadata {0}")
    @ValueSource(ints = {3, 5})
    void parameterizedMetadataScaleUp(int metadata) {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(metadata, 3, "v1"),
                status(1, 3, 1), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.SCALE_UP);
    }

    @ParameterizedTest(name = "storage {0}")
    @ValueSource(ints = {3, 6})
    void parameterizedStorageScaleUp(int storage) {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, storage, "v1"),
                status(3, 1, 1), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.SCALE_UP);
    }

    @Test
    void scaleDownPlanned() {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v1"),
                status(5, 6, 1), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.SCALE_DOWN);
    }

    @Test
    void staleGenerationTriggersUpgrade() {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v2"),
                status(3, 3, 1), 2);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.UPGRADE);
        assertThat(actions).extracting(OperatorAction::detail)
                .anyMatch(detail -> detail.contains("v2"));
    }

    @Test
    void backupScheduleTriggersBackup() {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(3, 3,
                List.of("r1"), "v1", "0 2 * * *", 168);
        List<OperatorAction> actions = planner.plan(spec,
                status(3, 3, 1), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.TRIGGER_BACKUP);
    }

    @Test
    void actionOrderCreateBeforeScale() {
        OperatorPlanner planner = new OperatorPlanner();
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v1"),
                status(0, 0, 0), 1);
        assertThat(actions.get(0).type())
                .isEqualTo(OperatorAction.ActionType.CREATE);
    }

    @Test
    void upgradeBeforeBackup() {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(3, 3,
                List.of("r1"), "v2", "0 2 * * *", 168);
        List<OperatorAction> actions = planner.plan(spec,
                status(3, 3, 1), 2);
        int upgradeIndex = indexOf(actions,
                OperatorAction.ActionType.UPGRADE);
        int backupIndex = indexOf(actions,
                OperatorAction.ActionType.TRIGGER_BACKUP);
        assertThat(upgradeIndex).isLessThan(backupIndex);
    }

    @ParameterizedTest(name = "metadata {0}")
    @ValueSource(ints = {0, 2, 4})
    void evenMetadataReplicasRejected(int metadata) {
        OperatorPlanner planner = new OperatorPlanner();
        assertThatThrownBy(() -> planner.plan(spec(metadata, 3, "v1"),
                status(0, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void zeroStorageReplicasRejected() {
        OperatorPlanner planner = new OperatorPlanner();
        assertThatThrownBy(() -> planner.plan(spec(3, 0, "v1"),
                status(0, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void emptyRegionsRejected() {
        OperatorPlanner planner = new OperatorPlanner();
        assertThatThrownBy(() -> planner.plan(
                new TieringKVClusterSpec(3, 3, List.of(), "v1",
                        null, 168), status(0, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankImageRejected() {
        OperatorPlanner planner = new OperatorPlanner();
        assertThatThrownBy(() -> planner.plan(spec(3, 3, " "),
                status(0, 0, 0), 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void controllerAppliesPlanToSink() {
        List<OperatorAction> applied = new ArrayList<>();
        TieringKVController controller =
                new TieringKVController(applied::add);
        long generation = controller.applySpec(spec(3, 3, "v1"),
                status(0, 0, 0));
        assertThat(generation).isEqualTo(1);
        assertThat(applied).isNotEmpty();
    }

    @ParameterizedTest(name = "generation {0}")
    @ValueSource(longs = {1, 2, 5})
    void parameterizedGenerationAdvance(long count) {
        List<OperatorAction> applied = new ArrayList<>();
        TieringKVController controller =
                new TieringKVController(applied::add);
        long generation = 0;
        for (long i = 0; i < count; i++) {
            generation = controller.applySpec(spec(3, 3, "v1"),
                    status(3, 3, generation));
        }
        assertThat(generation).isEqualTo(count);
    }

    @Test
    void replaceFailedNodePlanned() {
        OperatorPlanner planner = new OperatorPlanner();
        // readyStorage=2 < 3 且非空集群：先 SCALE_UP（补齐），
        // 替换语义由 UPGRADE/controller 层接管。
        List<OperatorAction> actions = planner.plan(spec(3, 3, "v1"),
                status(3, 2, 1), 1);
        assertThat(actions).extracting(OperatorAction::type)
                .contains(OperatorAction.ActionType.SCALE_UP);
    }

    private static int indexOf(List<OperatorAction> actions,
                               OperatorAction.ActionType type) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).type() == type) {
                return i;
            }
        }
        return -1;
    }
}
