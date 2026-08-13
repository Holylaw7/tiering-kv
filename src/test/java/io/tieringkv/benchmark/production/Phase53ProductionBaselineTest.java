package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.command.CommandRegistry;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 53 生产基线：接线/事务/高级命令面基线。 */
class Phase53ProductionBaselineTest {

    @Test
    void commandRegistryExpanded() {
        assertThat(CommandRegistry.createDefault().size())
                .isGreaterThan(95);
    }

    @Test
    void txnAndAdvancedCommandsRegistered() {
        CommandRegistry registry =
                CommandRegistry.createDefault();
        assertThat(registry.find("multi")).isNotNull();
        assertThat(registry.find("exec")).isNotNull();
        assertThat(registry.find("discard")).isNotNull();
        assertThat(registry.find("watch")).isNotNull();
        assertThat(registry.find("hscan")).isNotNull();
        assertThat(registry.find("linsert")).isNotNull();
        assertThat(registry.find("lmove")).isNotNull();
        assertThat(registry.find("rpoplpush")).isNotNull();
        assertThat(registry.find("zrangebylex")).isNotNull();
        assertThat(registry.find("zlexcount")).isNotNull();
        assertThat(registry.find("zremrangebylex")).isNotNull();
    }

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.5.0-rc1", 8, 15, 25, 9, 16, 26,
                150_000, 2048, 3, 5, 0, "LOCAL",
                "resp3 wiring and txn baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void commandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<Arguments> commands() {
        return Stream.of("hello", "multi", "exec", "discard",
                        "watch", "hscan", "linsert", "lmove",
                        "rpoplpush", "zrangebylex", "zlexcount",
                        "zremrangebylex", "subscribe", "publish",
                        "psubscribe")
                .map(Arguments::of);
    }
}
