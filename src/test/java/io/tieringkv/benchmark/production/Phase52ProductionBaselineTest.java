package io.tieringkv.benchmark.production;

import io.tieringkv.benchmarks.ProductionBaselineRegressionArchive;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 52 生产基线：数据结构命令面扩充后的基线快照。 */
class Phase52ProductionBaselineTest {

    @Test
    void commandRegistryExpanded() {
        assertThat(CommandRegistry.createDefault().size())
                .isGreaterThan(80);
    }

    @Test
    void dataStructureSmoke() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "f", "v");
        runner.exec("rpush", "l", "a");
        runner.exec("sadd", "s", "m");
        runner.exec("zadd", "z", "1", "m");
        assertThat(runner.exec("type", "h")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
        assertThat(runner.exec("type", "l")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
        assertThat(runner.exec("type", "s")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
        assertThat(runner.exec("type", "z")).isNotInstanceOf(
                io.tieringkv.protocol.RespError.class);
    }

    @Test
    void baselineSnapshotLocal() {
        ProductionBaselineRegressionArchive archive =
                new ProductionBaselineRegressionArchive();
        archive.addSnapshot("v3.4.0-rc1", 8, 15, 25, 9, 16, 26,
                160_000, 2048, 3, 5, 0, "LOCAL",
                "data-structure local baseline");
        assertThat(archive.latest().scope()).isEqualTo("LOCAL");
    }

    @ParameterizedTest(name = "command {0}")
    @MethodSource("commands")
    void commandRegistered(String command) {
        assertThat(CommandRegistry.createDefault().find(command))
                .isNotNull();
    }

    static Stream<Arguments> commands() {
        return Stream.of("hset", "hget", "hdel", "hgetall",
                        "hincrby", "lpush", "rpush", "lpop",
                        "rpop", "lrange", "sadd", "srem", "sinter",
                        "sunion", "sdiff", "sinterstore", "zadd",
                        "zscore", "zrange", "zrevrange", "zincrby",
                        "zrangebyscore", "zcount", "zrank", "hello",
                        "subscribe", "publish", "psubscribe")
                .map(Arguments::of);
    }
}
