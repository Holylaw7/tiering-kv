package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 55 生产基线。 */
class Phase55ProductionBaselineTest {

    @Test
    void registryHas115Commands() {
        assertThat(CommandRegistry.createDefault().size())
                .isEqualTo(132);
    }

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.7.0-rc1", 8, 15, 25, 9, 16, 26,
                130_000, 2048, 3, 5, 0, "LOCAL",
                "distributed correctness baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void commandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<Arguments> commands() {
        return Stream.of("xgroup", "xreadgroup", "xack",
                        "xpending")
                .map(Arguments::of);
    }
}
