package io.tieringkv.sql.coprocessor;

import io.tieringkv.sql.coprocessor.MultiAgentPushdownCoordinator;
import io.tieringkv.sql.coprocessor.ReinforcementPushdownAgent
        .Action;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RL 多智能体下推协调器（ADR-0250）：加权 Q 聚合 + 反馈闭环。 */
class MultiAgentPushdownCoordinatorTest {

    @Test
    void registerAgent() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("point", agent(5), 1.0);
        assertThat(coordinator.agentCount()).isEqualTo(1);
    }

    @Test
    void federatedDecideHigherQ() {
        MultiAgentPushdownCoordinator coordinator =
                coordinator(5, 1.0, -5, 1.0);
        assertThat(coordinator.federatedDecide("a"))
                .isEqualTo(Action.PUSHDOWN);
    }

    @Test
    void weightedQ() {
        MultiAgentPushdownCoordinator coordinator =
                coordinator(5, 2.0, -5, 2.0);
        assertThat(coordinator.weightedQ(Action.PUSHDOWN))
                .isZero();
    }

    @Test
    void learnUpdatesAllAgents() {
        MultiAgentPushdownCoordinator coordinator =
                coordinator(0, 1.0, 0, 1.0);
        coordinator.learn("a", Action.PUSHDOWN, 10);
        assertThat(coordinator.weightedQ(Action.PUSHDOWN))
                .isGreaterThan(0);
    }

    @Test
    void unknownQueryRejected() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        assertThatThrownBy(() ->
                coordinator.federatedDecide("missing"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void invalidRegisterRejected() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        assertThatThrownBy(() -> coordinator.registerAgent(
                "", agent(1), 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.registerAgent(
                "a", null, 1.0))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> coordinator.registerAgent(
                "a", agent(1), -1))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void agentCount() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("a", agent(1), 1.0);
        coordinator.registerAgent("b", agent(1), 1.0);
        coordinator.registerAgent("c", agent(1), 1.0);
        assertThat(coordinator.agentCount()).isEqualTo(3);
    }

    @Test
    void federatedDecisionsCounted() {
        MultiAgentPushdownCoordinator coordinator =
                coordinator(5, 1.0, -5, 1.0);
        for (int i = 0; i < 10; i++) {
            coordinator.federatedDecide("a");
        }
        assertThat(coordinator.federatedDecisions())
                .isEqualTo(10);
    }

    @Test
    void weightZeroAgent() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("a", agent(10), 0.0);
        coordinator.registerAgent("b", agent(-10), 1.0);
        assertThat(coordinator.weightedQ(Action.PUSHDOWN))
                .isEqualTo(-10.0);
    }

    @Test
    void convergenceToPushdown() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("a", agent(0), 1.0);
        for (int i = 0; i < 500; i++) {
            coordinator.learn("a", Action.PUSHDOWN, 10);
        }
        assertThat(coordinator.federatedDecide("a"))
                .isEqualTo(Action.PUSHDOWN);
    }

    @Test
    void deterministicWeights() {
        MultiAgentPushdownCoordinator a =
                coordinator(5, 2.0, -5, 1.0);
        MultiAgentPushdownCoordinator b =
                coordinator(5, 2.0, -5, 1.0);
        for (int i = 0; i < 50; i++) {
            assertThat(a.federatedDecide("a"))
                    .isEqualTo(b.federatedDecide("a"));
        }
    }

    @Test
    void feedbackLoopImproves() {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("a", agent(0), 1.0);
        coordinator.registerAgent("b", agent(0), 1.0);
        for (int i = 0; i < 200; i++) {
            coordinator.learn("a", Action.PUSHDOWN, 5);
        }
        assertThat(coordinator.weightedQ(Action.PUSHDOWN))
                .isCloseTo(2.5,
                        org.assertj.core.data.Offset
                                .offset(0.1));
    }

    @ParameterizedTest(name = "q1={0} w1={1} q2={2} w2={3}")
    @CsvSource({
            "5,1,-5,1,PUSHDOWN",
            "10,2,0,1,PUSHDOWN",
            "3,3,-1,1,PUSHDOWN",
            "8,2,4,3,PUSHDOWN",
            "2,5,-2,1,PUSHDOWN",
            "7,2,3,2,PUSHDOWN",
            "9,1,1,4,PUSHDOWN",
            "4,4,4,4,PUSHDOWN",
            "1,10,-1,10,PUSHDOWN",
            "6,3,-6,3,PUSHDOWN",
            "2,2,1,2,PUSHDOWN",
            "5,4,1,1,PUSHDOWN",
            "8,1,7,1,PUSHDOWN",
            "3,5,-3,5,PUSHDOWN",
            "10,3,0,1,PUSHDOWN",
            "5,2,0,1,PUSHDOWN",
            "5,2,5,2,PUSHDOWN",
            "2,3,-2,3,PUSHDOWN",
            "1,1,-1,1,PUSHDOWN",
            "5,1,0,1,PUSHDOWN",
            "0,1,4,1,PUSHDOWN",
            "-5,1,-5,1,KEEP_LOCAL",
            "-10,2,0,1,KEEP_LOCAL",
            "0,1,-10,2,KEEP_LOCAL",
            "-5,2,0,1,KEEP_LOCAL",
            "-3,3,1,1,KEEP_LOCAL",
            "-8,2,-4,3,KEEP_LOCAL",
            "-2,5,2,1,KEEP_LOCAL",
            "-7,2,-3,2,KEEP_LOCAL",
            "-9,1,-1,4,KEEP_LOCAL",
            "-4,4,-4,4,KEEP_LOCAL",
            "-2,10,1,10,KEEP_LOCAL",
            "-6,3,2,3,KEEP_LOCAL",
            "-2,2,-1,2,KEEP_LOCAL",
            "-5,4,-1,1,KEEP_LOCAL",
            "-8,1,-7,1,KEEP_LOCAL",
            "-3,5,1,5,KEEP_LOCAL",
            "-10,3,0,1,KEEP_LOCAL",
            "-5,2,-5,2,KEEP_LOCAL",
            "-2,3,1,3,KEEP_LOCAL",
            "-2,1,1,1,KEEP_LOCAL"
    })
    void parameterizedFederatedDecision(double q1, double w1,
                                        double q2, double w2,
                                        String expected) {
        MultiAgentPushdownCoordinator coordinator =
                coordinator(q1, w1, q2, w2);
        Action action = coordinator.federatedDecide("a");
        assertThat(action.name()).isEqualTo(expected);
    }

    @ParameterizedTest(name = "agents {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5, 6, 7, 8, 9, 10,
            12, 15, 20})
    void parameterizedAgentCounts(int count) {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        for (int i = 0; i < count; i++) {
            coordinator.registerAgent("q" + i,
                    agent(i % 2 == 0 ? 5 : -5), 1.0);
        }
        assertThat(coordinator.agentCount()).isEqualTo(count);
        Action action = coordinator.federatedDecide("q0");
        assertThat(action).isNotNull();
    }

    @ParameterizedTest(name = "reward={0} agents={1}")
    @CsvSource({
            "10,1",
            "10,2",
            "10,4",
            "-10,1",
            "-10,2",
            "-10,4",
            "5,1",
            "5,2",
            "5,4",
            "-5,1",
            "-5,2",
            "-5,4",
            "20,2",
            "20,4",
            "-20,2",
            "-20,4",
            "3,3",
            "3,5",
            "-3,3",
            "-3,5",
            "15,2",
            "15,3",
            "-15,2",
            "-15,3",
            "7,2"
    })
    void parameterizedFeedbackLoop(double reward, int agents) {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        for (int i = 0; i < agents; i++) {
            coordinator.registerAgent("q" + i, agent(0), 1.0);
        }
        for (int i = 0; i < 500; i++) {
            coordinator.learn("q0", Action.PUSHDOWN, reward);
        }
        double expected = reward * Math.max(1.0 / agents,
                0.1);
        assertThat(coordinator.weightedQ(Action.PUSHDOWN))
                .isCloseTo(expected,
                        org.assertj.core.data.Offset
                                .offset(0.5));
    }

    private static MultiAgentPushdownCoordinator coordinator(
            double q1, double w1, double q2, double w2) {
        MultiAgentPushdownCoordinator coordinator =
                new MultiAgentPushdownCoordinator();
        coordinator.registerAgent("a", agent(q1), w1);
        coordinator.registerAgent("b", agent(q2), w2);
        return coordinator;
    }

    private static ReinforcementPushdownAgent agent(double q) {
        ReinforcementPushdownAgent agent =
                new ReinforcementPushdownAgent(1.0, 0.0, 1000);
        agent.learn(Action.PUSHDOWN, q);
        agent.learn(Action.KEEP_LOCAL, -q);
        return agent;
    }
}
