package io.tieringkv.platform;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 52 边缘矩阵：数据结构/RESP3/PubSub 边界行为。 */
class Phase52EdgeMatrixTest {

    @Test
    void getOnHashReturnsWrongType() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "f", "v");
        assertThat(runner.exec("get", "h"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void typeReportsDataStructures() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "f", "v");
        runner.exec("rpush", "l", "a");
        runner.exec("sadd", "s", "a");
        runner.exec("zadd", "z", "1", "a");
        assertThat(runner.exec("type", "h")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString("hash"));
        assertThat(runner.exec("type", "l")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString("list"));
        assertThat(runner.exec("type", "s")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString("set"));
        assertThat(runner.exec("type", "z")).isEqualTo(
                new io.tieringkv.protocol.RespSimpleString("zset"));
    }

    @Test
    void hincrbyOverflowError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("hset", "h", "f",
                Long.toString(Long.MAX_VALUE));
        assertThat(runner.exec("hincrby", "h", "f", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void lpopNegativeCountError() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("rpush", "l", "a");
        RespValue result = runner.exec("lpop", "l", "-1");
        assertThat(result).isInstanceOf(RespError.class);
    }

    @Test
    void zincrbyInfinityRejected() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("zincrby", "z",
                "inf", "m");
        assertThat(result).isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "edge {0}")
    @MethodSource("edges")
    void edgeMatrix(String edge) {
        switch (edge) {
            case "empty-hash-hgetall" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                RespArray result = (RespArray) runner.exec(
                        "hgetall", "nope");
                assertThat(result.values()).isEmpty();
            }
            case "hlen-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("hlen", "nope"))
                        .isEqualTo(new RespInteger(0));
            }
            case "lrange-missing-empty" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                RespArray result = (RespArray) runner.exec(
                        "lrange", "nope", "0", "-1");
                assertThat(result.values()).isEmpty();
            }
            case "scard-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("scard", "nope"))
                        .isEqualTo(new RespInteger(0));
            }
            case "zcard-missing-zero" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zcard", "nope"))
                        .isEqualTo(new RespInteger(0));
            }
            case "srandmember-missing-nil" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("srandmember", "nope"))
                        .isEqualTo(RespNull.BULK_STRING);
            }
            case "zscore-missing-nil" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zscore", "nope", "m"))
                        .isEqualTo(RespNull.BULK_STRING);
            }
            case "zrange-invalid-index-error" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zrange", "z", "x", "1"))
                        .isInstanceOf(RespError.class);
            }
            case "zadd-odd-args-error" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zadd", "z", "1", "a", "2"))
                        .isInstanceOf(RespError.class);
            }
            case "hmset-returns-ok" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("hmset", "h", "a", "1"))
                        .isEqualTo(new io.tieringkv.protocol
                                .RespSimpleString("OK"));
            }
            case "setop-single-key" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("sadd", "s", "a", "b");
                RespArray result = (RespArray) runner.exec(
                        "sunion", "s");
                assertThat(result.values()).hasSize(2);
            }
            case "sdiff-empty-result" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("sadd", "a", "1");
                runner.exec("sadd", "b", "1");
                RespArray result = (RespArray) runner.exec(
                        "sdiff", "a", "b");
                assertThat(result.values()).isEmpty();
            }
            case "ltrim-keeps-middle" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                runner.exec("rpush", "l", "a", "b", "c");
                runner.exec("ltrim", "l", "1", "1");
                RespArray result = (RespArray) runner.exec(
                        "lrange", "l", "0", "-1");
                assertThat(((RespBulkString) result.values()
                        .get(0)).bytes()).isEqualTo(
                        "b".getBytes(StandardCharsets.UTF_8));
            }
            case "hello-empty-args" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("hello"))
                        .isInstanceOf(RespArray.class);
            }
            case "publish-missing-args" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("publish", "ch"))
                        .isInstanceOf(RespError.class);
            }
            case "subscribe-no-args" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("subscribe"))
                        .isInstanceOf(RespError.class);
            }
            case "zincrby-returns-formatted" -> {
                TestCommandRunner runner =
                        new TestCommandRunner(MemTable.create());
                assertThat(runner.exec("zincrby", "z", "1", "m"))
                        .isEqualTo(new RespBulkString(
                                "1".getBytes(
                                        StandardCharsets.UTF_8)));
            }
            default -> throw new AssertionError(edge);
        }
    }

    static Stream<String> edges() {
        return Stream.of("empty-hash-hgetall", "hlen-missing-zero",
                "lrange-missing-empty", "scard-missing-zero",
                "zcard-missing-zero", "srandmember-missing-nil",
                "zscore-missing-nil", "zrange-invalid-index-error",
                "zadd-odd-args-error", "hmset-returns-ok",
                "setop-single-key", "sdiff-empty-result",
                "ltrim-keeps-middle", "hello-empty-args",
                "publish-missing-args", "subscribe-no-args",
                "zincrby-returns-formatted");
    }
}
