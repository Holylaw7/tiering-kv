package io.tieringkv.cli;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** tierctl CLI（Phase 26 Goal 7）：解析、校验与分发。 */
class TierCtlTest {

    private static final TierCtl.CliContext CONTEXT =
            new TierCtl.CliContext() {
                @Override
                public String clusterStatus() {
                    return "status:ok";
                }

                @Override
                public String regionList() {
                    return "r1,r2,r3";
                }

                @Override
                public String txnInspect(String txnId) {
                    return "txn:" + txnId;
                }

                @Override
                public String backupCreate(String name) {
                    return "backup:" + name;
                }

                @Override
                public String restore(String name) {
                    return "restore:" + name;
                }

                @Override
                public String chaosRun(String scenario) {
                    return "chaos:" + scenario;
                }

                @Override
                public String upgrade(String target) {
                    return "upgrade:" + target;
                }
            };

    @Test
    void clusterStatusParsesAndExecutes() {
        TierCtl.CliCommand command = TierCtl.parse(
                new String[]{"cluster", "status"});
        assertThat(command.command())
                .isEqualTo(TierCtl.Command.CLUSTER_STATUS);
        assertThat(TierCtl.execute(CONTEXT, command))
                .isEqualTo("status:ok");
    }

    @Test
    void regionListParsesAndExecutes() {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"region", "list"})).isEqualTo("r1,r2,r3");
    }

    @ParameterizedTest(name = "txn {0}")
    @ValueSource(strings = {"t1", "txn-99", "long-txn-id-0001"})
    void txnInspect(String txnId) {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"txn", "inspect", txnId}))
                .isEqualTo("txn:" + txnId);
    }

    @ParameterizedTest(name = "backup {0}")
    @ValueSource(strings = {"daily", "pre-upgrade", "b1"})
    void backupCreate(String name) {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"backup", "create", name}))
                .isEqualTo("backup:" + name);
    }

    @ParameterizedTest(name = "restore {0}")
    @ValueSource(strings = {"daily", "snap-20260811"})
    void restore(String name) {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"restore", name}))
                .isEqualTo("restore:" + name);
    }

    @ParameterizedTest(name = "scenario {0}")
    @ValueSource(strings = {"partition", "disk-full", "kill-meta"})
    void chaosRun(String scenario) {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"chaos", "run", scenario}))
                .isEqualTo("chaos:" + scenario);
    }

    @ParameterizedTest(name = "target {0}")
    @ValueSource(strings = {"v1.0.0", "v1.0.1-rc1"})
    void upgrade(String target) {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"upgrade", target}))
                .isEqualTo("upgrade:" + target);
    }

    @Test
    void tooFewArgsRejected() {
        assertThatThrownBy(() -> TierCtl.parse(new String[]{"cluster"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TierCtl.parse(new String[]{}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TierCtl.parse(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void unknownCommandRejected() {
        assertThatThrownBy(() -> TierCtl.parse(
                new String[]{"unknown", "cmd"}))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TierCtl.parse(
                new String[]{"cluster", "bogus"}))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void missingArgumentRejected() {
        TierCtl.CliCommand command = TierCtl.parse(
                new String[]{"txn", "inspect"});
        assertThatThrownBy(() -> TierCtl.execute(CONTEXT, command))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void caseInsensitiveCommands() {
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"CLUSTER", "STATUS"})).isEqualTo("status:ok");
        assertThat(TierCtl.run(CONTEXT,
                new String[]{"Region", "List"})).isEqualTo("r1,r2,r3");
    }

    @Test
    void extraArgsPreserved() {
        TierCtl.CliCommand command = TierCtl.parse(
                new String[]{"backup", "create", "daily", "--force"});
        assertThat(command.args()).isEqualTo(List.of("daily", "--force"));
    }
}
