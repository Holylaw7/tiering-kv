package io.tieringkv.runtime;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 滚动升级边界（ADR-0098）：quorum 丢失位置、中断、异常传播。 */
class UpgradeCoordinatorEdgeTest {

    @Test
    void zeroNodesUpgradeSucceeds() {
        AtomicInteger upgraded = new AtomicInteger();
        assertThat(UpgradeCoordinator.rollingUpgrade(List.of(),
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 100)).isTrue();
        assertThat(upgraded.get()).isZero();
    }

    @Test
    void singleNodeUpgradeSucceeds() {
        AtomicInteger upgraded = new AtomicInteger();
        assertThat(UpgradeCoordinator.rollingUpgrade(List.of("n1"),
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 100)).isTrue();
        assertThat(upgraded.get()).isEqualTo(1);
    }

    @Test
    void quorumLostBeforeFirstNodeAborts() {
        AtomicInteger upgraded = new AtomicInteger();
        boolean done = UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b", "c"), node -> upgraded.incrementAndGet(),
                () -> false, () -> true, 100);
        assertThat(done).isFalse();
        assertThat(upgraded.get()).isZero();
    }

    @ParameterizedTest(name = "position {0}")
    @ValueSource(ints = {1, 2, 3, 4})
    void quorumLostAtPositionAborts(int position) {
        AtomicInteger upgraded = new AtomicInteger();
        AtomicBoolean quorum = new AtomicBoolean(true);
        boolean done = UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b", "c", "d"), node -> {
                    if (upgraded.incrementAndGet() == position) {
                        quorum.set(false);
                    }
                }, quorum::get, () -> true, 100);
        // 最后一位升级完成后无法再检测 quorum 丢失（升级已完成）。
        assertThat(done).isEqualTo(position == 4);
        assertThat(upgraded.get()).isEqualTo(position);
    }

    @Test
    void upgradeExceptionPropagates() {
        assertThatThrownBy(() -> UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b"), node -> {
                    throw new IllegalStateException("upgrade failed");
                }, () -> true, () -> true, 100))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void catchupInterruptedAborts() throws Exception {
        AtomicBoolean interrupted = new AtomicBoolean();
        Thread upgrade = new Thread(() -> {
            boolean done = UpgradeCoordinator.rollingUpgrade(
                    List.of("a"), node -> {
                    }, () -> true, () -> false, 10_000);
            interrupted.set(!done);
        });
        upgrade.start();
        Thread.sleep(100);
        upgrade.interrupt();
        upgrade.join(2_000);
        assertThat(interrupted.get()).isTrue();
    }

    @Test
    void allCaughtUpImmediatelyUpgradesAll() {
        AtomicInteger upgraded = new AtomicInteger();
        assertThat(UpgradeCoordinator.rollingUpgrade(
                List.of("a", "b", "c", "d", "e"),
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 100)).isTrue();
        assertThat(upgraded.get()).isEqualTo(5);
    }

    @Test
    void zeroWaitWithCaughtUpSucceeds() {
        AtomicInteger upgraded = new AtomicInteger();
        assertThat(UpgradeCoordinator.rollingUpgrade(List.of("a"),
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 0)).isTrue();
    }

    @Test
    void zeroWaitWithoutCaughtUpAborts() {
        AtomicInteger upgraded = new AtomicInteger();
        assertThat(UpgradeCoordinator.rollingUpgrade(List.of("a"),
                node -> upgraded.incrementAndGet(),
                () -> true, () -> false, 0)).isFalse();
        assertThat(upgraded.get()).isEqualTo(1);
    }

    @Test
    void upgradeAllThenQuorumLossIsUndetectedUntilNextCall() {
        AtomicInteger upgraded = new AtomicInteger();
        List<String> nodes = new ArrayList<>();
        for (int i = 0; i < 3; i++) {
            nodes.add("n" + i);
        }
        assertThat(UpgradeCoordinator.rollingUpgrade(nodes,
                node -> upgraded.incrementAndGet(),
                () -> true, () -> true, 100)).isTrue();
        assertThat(UpgradeCoordinator.rollingUpgrade(nodes,
                node -> {
                }, () -> false, () -> true, 100)).isFalse();
    }
}
