package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.ci.GateConvergenceV16;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 51 生产基线：命令面扩充后的基线快照。 */
class Phase51ProductionBaselineTest {

    @Test
    void commandRegistryExpanded() {
        assertThat(CommandRegistry.createDefault().size())
                .isGreaterThan(35);
    }

    @Test
    void coreCommandsSmoke() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "k", "1");
        assertThat(runner.exec("incr", "k")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
        assertThat(runner.exec("ttl", "k")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
        assertThat(runner.exec("scan", "0")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
    }

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.3.0-rc1", 8, 15, 25, 9, 16, 26,
                180_000, 2048, 3, 5, 0, "LOCAL",
                "command-family local baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @Test
    void gateFinalDispositionsHeld() {
        assertThat(GateConvergenceV16.finalBlockedCount())
                .isGreaterThan(10);
        assertThat(GateConvergenceV16.closedCount())
                .isGreaterThan(5);
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void commandSmoke(String command) {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<Arguments> commands() {
        return Stream.of("incr", "decr", "incrby", "decrby",
                        "append", "strlen", "getset", "setnx",
                        "setex", "psetex", "getdel", "getrange",
                        "setrange", "ttl", "pttl", "expire",
                        "pexpire", "expireat", "pexpireat",
                        "persist", "mget", "mset", "msetnx",
                        "dbsize", "flushdb", "flushall", "scan",
                        "type", "config", "client", "command")
                .map(Arguments::of);
    }
}
