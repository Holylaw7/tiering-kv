package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent
        .Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.Random;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RL 动态下推智能体（ADR-0243）：Q 学习在线决策。 */
class ReinforcementPushdownAgentTest {

    @Test
    void exploreChoosesBothActions() {
        ReinforcementPushdownAgent agent = agent(0.5, 1.0, 100);
        boolean sawPushdown = false;
        boolean sawLocal = false;
        for (int i = 0; i < 200; i++) {
            Action action = agent.decide();
            sawPushdown |= action == Action.PUSHDOWN;
            sawLocal |= action == Action.KEEP_LOCAL;
        }
        assertThat(sawPushdown).isTrue();
        assertThat(sawLocal).isTrue();
    }

    @Test
    void greedyChoosesHigherQ() {
        ReinforcementPushdownAgent agent = agent(1.0, 0.0, 100);
        agent.learn(Action.PUSHDOWN, 10);
        assertThat(agent.decide()).isEqualTo(Action.PUSHDOWN);
    }

    @Test
    void learnUpdatesQ() {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        agent.learn(Action.PUSHDOWN, 10);
        assertThat(agent.q(Action.PUSHDOWN)).isEqualTo(5.0);
    }

    @Test
    void pushdownCounted() {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        agent.learn(Action.PUSHDOWN, 1);
        agent.learn(Action.KEEP_LOCAL, 1);
        assertThat(agent.pushdowns()).isEqualTo(1);
    }

    @Test
    void decisionsCounted() {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        for (int i = 0; i < 10; i++) {
            agent.decide();
        }
        assertThat(agent.decisions()).isEqualTo(10);
    }

    @Test
    void invalidParamsRejected() {
        assertThatThrownBy(() -> agent(0, 0.5, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent(0.5, 1.1, 10))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> agent(0.5, 0.5, 0))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullActionRejected() {
        assertThatThrownBy(() -> agent(0.5, 0.5, 10)
                .learn(null, 1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rewardPositiveRaisesQ() {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        agent.learn(Action.PUSHDOWN, 10);
        double before = agent.q(Action.PUSHDOWN);
        agent.learn(Action.PUSHDOWN, 10);
        assertThat(agent.q(Action.PUSHDOWN))
                .isGreaterThan(before);
    }

    @Test
    void rewardNegativeLowersQ() {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        agent.learn(Action.PUSHDOWN, 10);
        double before = agent.q(Action.PUSHDOWN);
        agent.learn(Action.PUSHDOWN, -10);
        assertThat(agent.q(Action.PUSHDOWN))
                .isLessThan(before);
    }

    @Test
    void clampToBound() {
        ReinforcementPushdownAgent agent = agent(1.0, 0.0, 5);
        agent.learn(Action.PUSHDOWN, 100);
        assertThat(agent.q(Action.PUSHDOWN)).isEqualTo(5.0);
        agent.learn(Action.PUSHDOWN, -100);
        assertThat(agent.q(Action.PUSHDOWN))
                .isEqualTo(-5.0);
    }

    @Test
    void deterministicWithSeed() {
        ReinforcementPushdownAgent a = agent(0.5, 0.3, 10, 42);
        ReinforcementPushdownAgent b = agent(0.5, 0.3, 10, 42);
        for (int i = 0; i < 50; i++) {
            assertThat(a.decide()).isEqualTo(b.decide());
        }
    }

    @Test
    void convergenceToPushdown() {
        ReinforcementPushdownAgent agent = agent(0.8, 0.1, 100);
        for (int i = 0; i < 1000; i++) {
            agent.learn(Action.PUSHDOWN, 10);
        }
        int pushdowns = 0;
        for (int i = 0; i < 100; i++) {
            if (agent.decide() == Action.PUSHDOWN) {
                pushdowns++;
            }
        }
        assertThat(pushdowns).isGreaterThan(85);
    }

    @ParameterizedTest(name = "lr={0} reward={1}")
    @CsvSource({
            "0.1,10,1",
            "0.2,10,2",
            "0.5,10,5",
            "0.8,10,8",
            "1.0,10,10",
            "0.1,-10,-1",
            "0.2,-10,-2",
            "0.5,-10,-5",
            "0.8,-10,-8",
            "1.0,-10,-10",
            "0.1,5,0.5",
            "0.2,5,1",
            "0.5,5,2.5",
            "0.8,5,4",
            "1.0,5,5",
            "0.1,20,2",
            "0.2,20,4",
            "0.5,20,10",
            "0.8,20,16",
            "1.0,20,20",
            "0.25,8,2",
            "0.25,-8,-2",
            "0.75,12,9",
            "0.75,-12,-9",
            "0.4,15,6",
            "0.4,-15,-6",
            "0.6,3,1.8",
            "0.6,-3,-1.8",
            "0.3,30,9",
            "0.3,-30,-9",
            "0.9,7,6.3",
            "0.9,-7,-6.3",
            "0.15,50,7.5",
            "0.15,-50,-7.5",
            "0.7,11,7.7",
            "0.7,-11,-7.7",
            "0.45,9,4.05",
            "0.45,-9,-4.05",
            "0.85,2,1.7",
            "0.85,-2,-1.7"
    })
    void parameterizedSingleStepQ(double lr, double reward,
                                  double expectedQ) {
        ReinforcementPushdownAgent agent = agent(lr, 0.0, 100);
        agent.learn(Action.PUSHDOWN, reward);
        assertThat(agent.q(Action.PUSHDOWN))
                .isCloseTo(expectedQ,
                        org.assertj.core.data.Offset
                                .offset(1e-9));
    }

    @ParameterizedTest(name = "epsilon {0}")
    @ValueSource(doubles = {0, 0.05, 0.1, 0.15, 0.2, 0.25,
            0.3, 0.4, 0.5, 0.6, 0.7, 0.8, 0.9})
    void parameterizedEpsilonValues(double epsilon) {
        ReinforcementPushdownAgent agent = agent(0.5, epsilon,
                10);
        agent.learn(Action.PUSHDOWN, 5);
        for (int i = 0; i < 100; i++) {
            agent.decide();
        }
        assertThat(agent.decisions()).isEqualTo(100);
    }

    @ParameterizedTest(name = "reward={0} steps={1}")
    @CsvSource({
            "10,5",
            "10,10",
            "10,20",
            "-10,5",
            "-10,10",
            "-10,20",
            "5,5",
            "5,10",
            "5,20",
            "-5,5",
            "-5,10",
            "-5,20",
            "20,5",
            "20,10",
            "20,20",
            "-20,5",
            "-20,10",
            "-20,20",
            "3,10",
            "3,20",
            "-3,10",
            "-3,20",
            "15,10",
            "15,20",
            "-15,20"
    })
    void parameterizedConvergence(double reward, int steps) {
        ReinforcementPushdownAgent agent = agent(0.5, 0.0, 100);
        for (int i = 0; i < steps; i++) {
            agent.learn(Action.PUSHDOWN, reward);
        }
        assertThat(agent.q(Action.PUSHDOWN))
                .isCloseTo(reward,
                        org.assertj.core.data.Offset
                                .offset(Math.abs(reward)
                                        * 0.05 + 0.001));
    }

    private static ReinforcementPushdownAgent agent(
            double lr, double epsilon, double bound) {
        return new ReinforcementPushdownAgent(lr, epsilon,
                bound);
    }

    private static ReinforcementPushdownAgent agent(
            double lr, double epsilon, double bound, long seed) {
        return new ReinforcementPushdownAgent(lr, epsilon,
                bound, new Random(seed));
    }
}
