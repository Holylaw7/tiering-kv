package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.CsvSource;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** TTL 命令族（ADR-0270）。 */
class TtlCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void ttlOnMissingKeyReturnsMinusTwo() {
        assertThat(runner().exec("ttl", "nope")).isEqualTo(
                new RespInteger(-2));
    }

    @Test
    void ttlWithoutTtlReturnsMinusOne() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("ttl", "k")).isEqualTo(
                new RespInteger(-1));
    }

    @Test
    void expireSetsPositiveTtl() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("expire", "k", "100")).isEqualTo(
                new RespInteger(1));
        long ttl = ((RespInteger) runner.exec("ttl", "k")).value();
        assertThat(ttl).isBetween(0L, 100L);
    }

    @Test
    void expireOnMissingKeyReturnsZero() {
        assertThat(runner().exec("expire", "nope", "100"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void expireZeroDeletesKey() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("expire", "k", "0")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("exists", "k")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void persistRemovesTtl() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        runner.exec("expire", "k", "100");
        assertThat(runner.exec("persist", "k")).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("ttl", "k")).isEqualTo(
                new RespInteger(-1));
    }

    @Test
    void persistWithoutTtlReturnsZero() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("persist", "k")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void pttlReturnsMillis() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        runner.exec("pexpire", "k", "5000");
        long pttl = ((RespInteger) runner.exec("pttl", "k")).value();
        assertThat(pttl).isBetween(0L, 5000L);
    }

    @Test
    void expireAtPastDeletes() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        long past = System.currentTimeMillis() / 1000 - 10;
        assertThat(runner.exec("expireat", "k", past)).isEqualTo(
                new RespInteger(1));
        assertThat(runner.exec("exists", "k")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void nonIntegerTtlRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("expire", "k", "abc"))
                .isInstanceOf(RespError.class);
    }

    @ParameterizedTest(name = "ttl command {0} arg {1}")
    @CsvSource({
            "expire, 1",
            "expire, 10",
            "expire, 1000",
            "pexpire, 500",
            "pexpire, 5000",
            "expireat, 1",
            "pexpireat, 5000"
    })
    void ttlSetCommandsReturnOne(String command, String value) {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        RespValue result;
        if ("expireat".equals(command)) {
            result = runner.exec(command, "k",
                    (System.currentTimeMillis() / 1000) + 100);
        } else if ("pexpireat".equals(command)) {
            result = runner.exec(command, "k",
                    System.currentTimeMillis() + 100_000);
        } else {
            result = runner.exec(command, "k", value);
        }
        assertThat(result).isEqualTo(new RespInteger(1));
    }

    @ParameterizedTest(name = "ttl window {0}")
    @MethodSource("ttlWindows")
    void ttlWindowAfterExpire(String command, String value,
                              long maxMillis) {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        if ("expireat".equals(command)) {
            runner.exec(command, "k",
                    (System.currentTimeMillis() / 1000)
                            + Long.parseLong(value));
        } else if ("pexpireat".equals(command)) {
            runner.exec(command, "k",
                    System.currentTimeMillis()
                            + Long.parseLong(value));
        } else {
            runner.exec(command, "k", value);
        }
        long pttl = ((RespInteger) runner.exec("pttl", "k")).value();
        assertThat(pttl).isBetween(0L, maxMillis);
        long ttl = ((RespInteger) runner.exec("ttl", "k")).value();
        assertThat(ttl).isBetween(0L, maxMillis / 1000 + 1);
    }

    @ParameterizedTest(name = "persist {0}")
    @MethodSource("persistMatrix")
    void persistMatrix(String setup, String expected) {
        TestCommandRunner runner = runner();
        if ("with-ttl".equals(setup)) {
            runner.exec("set", "k", "v");
            runner.exec("expire", "k", "100");
        } else if ("no-ttl".equals(setup)) {
            runner.exec("set", "k", "v");
        }
        RespValue result = runner.exec("persist", "k");
        assertThat(((RespInteger) result).value())
                .isEqualTo(Long.parseLong(expected));
    }

    @ParameterizedTest(name = "expired treated missing {0}")
    @MethodSource("expiredCases")
    void expiredKeyTreatedAsMissing(String setup) {
        TestCommandRunner runner = runner();
        switch (setup) {
            case "setex-zero" -> {
                runner.exec("setex", "k", "0", "v");
                assertThat(runner.exec("exists", "k")).isEqualTo(
                        new RespInteger(0));
                assertThat(runner.exec("ttl", "k")).isEqualTo(
                        new RespInteger(-2));
            }
            case "expire-zero" -> {
                runner.exec("set", "k", "v");
                runner.exec("expire", "k", "0");
                assertThat(runner.exec("type", "k")).isEqualTo(
                        new io.tieringkv.protocol.RespSimpleString(
                                "none"));
            }
            case "expireat-past" -> {
                runner.exec("set", "k", "v");
                runner.exec("expireat", "k",
                        System.currentTimeMillis() / 1000 - 5);
                assertThat(runner.exec("get", "k")).isEqualTo(
                        io.tieringkv.protocol.RespNull.BULK_STRING);
            }
            default -> throw new AssertionError(setup);
        }
    }

    @ParameterizedTest(name = "wal ttl {0}")
    @MethodSource("walTtlOps")
    void walTtlSemantics(String op) throws Exception {
        Path dir = Files.createTempDirectory("wal-ttl");
        WALManager wal = new WALManager(WALConfig.defaults(dir));
        TestCommandRunner runner = new TestCommandRunner(
                new WALStorageEngine(wal, MemTable.create()));
        runner.exec("set", "k", "v");
        switch (op) {
            case "expire" -> {
                runner.exec("expire", "k", "100");
                assertThat(((RespInteger) runner.exec("ttl", "k"))
                        .value()).isBetween(0L, 100L);
            }
            case "pexpire" -> {
                runner.exec("pexpire", "k", "5000");
                assertThat(((RespInteger) runner.exec("pttl", "k"))
                        .value()).isBetween(0L, 5000L);
            }
            case "persist" -> {
                runner.exec("expire", "k", "100");
                runner.exec("persist", "k");
                assertThat(runner.exec("ttl", "k")).isEqualTo(
                        new RespInteger(-1));
            }
            case "expireat" -> {
                runner.exec("expireat", "k",
                        (System.currentTimeMillis() / 1000) + 100);
                assertThat(runner.exec("exists", "k")).isEqualTo(
                        new RespInteger(1));
            }
            default -> throw new AssertionError(op);
        }
        wal.close();
    }

    static Stream<Arguments> ttlWindows() {
        return Stream.of(
                Arguments.of("expire", "1", 1000L),
                Arguments.of("expire", "10", 10_000L),
                Arguments.of("expire", "100", 100_000L),
                Arguments.of("pexpire", "500", 500L),
                Arguments.of("pexpire", "5000", 5000L),
                Arguments.of("pexpire", "50000", 50_000L),
                Arguments.of("expireat", "100", 100_000L),
                Arguments.of("pexpireat", "5000", 5000L),
                Arguments.of("expire", "1000", 1_000_000L));
    }

    static Stream<Arguments> persistMatrix() {
        return Stream.of(
                Arguments.of("with-ttl", "1"),
                Arguments.of("no-ttl", "0"),
                Arguments.of("missing", "0"));
    }

    static Stream<Arguments> expiredCases() {
        return Stream.of("setex-zero", "expire-zero",
                        "expireat-past")
                .map(Arguments::of);
    }

    static Stream<Arguments> walTtlOps() {
        return Stream.of("expire", "pexpire", "persist",
                        "expireat")
                .map(Arguments::of);
    }
}
