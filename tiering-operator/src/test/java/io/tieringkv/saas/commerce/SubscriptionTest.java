package io.tieringkv.saas.commerce;

import io.tieringkv.saas.commerce.Subscription.Snapshot;
import io.tieringkv.saas.commerce.Subscription.State;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** SaaS 订阅状态机（ADR-0146）：TRIAL/ACTIVE/CANCELED 转换矩阵。 */
class SubscriptionTest {

    @Test
    void trialSnapshot() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.TRIAL,
                0, 1_000);
        assertThat(snapshot.state()).isEqualTo(State.TRIAL);
        assertThat(snapshot.cycle()).isZero();
    }

    @Test
    void activeSnapshot() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.ACTIVE,
                3, 1_000);
        assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void trialActivate() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.TRIAL,
                0, 1_000).activate();
        assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void activeCancel() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.ACTIVE,
                2, 1_000).cancel();
        assertThat(snapshot.state()).isEqualTo(State.CANCELED);
    }

    @Test
    void trialCancel() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.TRIAL,
                0, 1_000).cancel();
        assertThat(snapshot.state()).isEqualTo(State.CANCELED);
    }

    @Test
    void canceledCannotActivate() {
        Snapshot canceled = new Snapshot("t1", "p1", State.CANCELED,
                1, 1_000);
        assertThatThrownBy(canceled::activate)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void canceledCannotRenew() {
        Snapshot canceled = new Snapshot("t1", "p1", State.CANCELED,
                1, 1_000);
        assertThatThrownBy(canceled::renew)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void trialCannotRenew() {
        Snapshot trial = new Snapshot("t1", "p1", State.TRIAL,
                0, 1_000);
        assertThatThrownBy(trial::renew)
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void activeRenewIncrementsCycle() {
        Snapshot renewed = new Snapshot("t1", "p1", State.ACTIVE,
                2, 1_000).renew();
        assertThat(renewed.cycle()).isEqualTo(3);
        assertThat(renewed.state()).isEqualTo(State.ACTIVE);
    }

    @Test
    void renewKeepsTenantAndPlan() {
        Snapshot renewed = new Snapshot("t1", "p1", State.ACTIVE,
                5, 7_000).renew();
        assertThat(renewed.tenantId()).isEqualTo("t1");
        assertThat(renewed.planId()).isEqualTo("p1");
        assertThat(renewed.startMillis()).isEqualTo(7_000);
    }

    @Test
    void blankTenantRejected() {
        assertThatThrownBy(() -> new Snapshot("", "p1", State.ACTIVE,
                0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void blankPlanRejected() {
        assertThatThrownBy(() -> new Snapshot("t1", " ", State.ACTIVE,
                0, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @ParameterizedTest(name = "cycle {0}")
    @ValueSource(longs = {0, 1, 100})
    void parameterizedCyclesAccepted(long cycle) {
        Snapshot snapshot = new Snapshot("t1", "p1", State.ACTIVE,
                cycle, 1_000);
        assertThat(snapshot.cycle()).isEqualTo(cycle);
    }

    @ParameterizedTest(name = "negative cycle {0}")
    @ValueSource(longs = {-1, -100})
    void parameterizedNegativeCyclesRejected(long cycle) {
        assertThatThrownBy(() -> new Snapshot("t1", "p1",
                State.ACTIVE, cycle, 1_000))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void snapshotsAreImmutable() {
        Snapshot snapshot = new Snapshot("t1", "p1", State.ACTIVE,
                0, 1_000);
        assertThat(snapshot.activate()).isNotSameAs(snapshot);
        assertThat(snapshot.cancel()).isNotSameAs(snapshot);
        assertThat(snapshot.state()).isEqualTo(State.ACTIVE);
    }
}
