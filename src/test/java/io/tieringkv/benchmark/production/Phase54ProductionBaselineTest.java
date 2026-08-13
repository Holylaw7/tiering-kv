package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 54 生产基线：事务/Stream 面基线。 */
class Phase54ProductionBaselineTest {

    @Test
    void commandRegistryExpanded() {
        assertThat(CommandRegistry.createDefault().size())
                .isGreaterThan(105);
    }

    @Test
    void txnAndStreamCommandsRegistered() {
        CommandRegistry registry =
                CommandRegistry.createDefault();
        assertThat(registry.find("watch")).isNotNull();
        assertThat(registry.find("unwatch")).isNotNull();
        assertThat(registry.find("xadd")).isNotNull();
        assertThat(registry.find("xread")).isNotNull();
        assertThat(registry.find("xlen")).isNotNull();
        assertThat(registry.find("xrange")).isNotNull();
        assertThat(registry.find("xtrim")).isNotNull();
        assertThat(registry.find("blpop")).isNotNull();
        assertThat(registry.find("brpop")).isNotNull();
    }

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.6.0-rc1", 8, 15, 25, 9, 16, 26,
                140_000, 2048, 3, 5, 0, "LOCAL",
                "txn stream production baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void commandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<Arguments> commands() {
        return Stream.of("watch", "unwatch", "xadd", "xread",
                        "xlen", "xrange", "xtrim", "blpop",
                        "brpop")
                .map(Arguments::of);
    }
}
