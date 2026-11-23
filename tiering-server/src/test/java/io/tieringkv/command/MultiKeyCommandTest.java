package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 多键命令族（ADR-0271）。 */
class MultiKeyCommandTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create());
    }

    @Test
    void mgetMissingKeysReturnsNils() {
        RespValue result = runner().exec("mget", "a", "b", "c");
        assertThat(((RespArray) result).values())
                .containsExactly(RespNull.BULK_STRING,
                        RespNull.BULK_STRING, RespNull.BULK_STRING);
    }

    @Test
    void msetThenMgetRoundTrip() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("mset", "a", "1", "b", "2"))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue result = runner.exec("mget", "a", "b");
        assertThat(((RespArray) result).values()).hasSize(2);
        assertThat(((RespBulkString) ((RespArray) result)
                .values().get(0)).bytes()).isEqualTo(
                "1".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void msetnxAllNewReturnsOne() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("msetnx", "a", "1", "b", "2"))
                .isEqualTo(new RespInteger(1));
    }

    @Test
    void msetnxAnyExistingReturnsZero() {
        TestCommandRunner runner = runner();
        runner.exec("set", "a", "existing");
        assertThat(runner.exec("msetnx", "a", "1", "b", "2"))
                .isEqualTo(new RespInteger(0));
        assertThat(runner.exec("get", "a")).isEqualTo(
                new RespBulkString("existing".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void delMultiReturnsCount() {
        TestCommandRunner runner = runner();
        runner.exec("mset", "a", "1", "b", "2", "c", "3");
        assertThat(runner.exec("del", "a", "b", "nope"))
                .isEqualTo(new RespInteger(2));
    }

    @Test
    void existsMultiReturnsCount() {
        TestCommandRunner runner = runner();
        runner.exec("mset", "a", "1", "b", "2");
        assertThat(runner.exec("exists", "a", "b", "nope"))
                .isEqualTo(new RespInteger(2));
    }

    @Test
    void msetOddArgsRejected() {
        assertThat(runner().exec("mset", "a", "1", "b"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void mgetEmptyRejected() {
        assertThat(runner().exec("mget")).isInstanceOf(
                RespError.class);
    }

    @ParameterizedTest(name = "mget matrix {0}")
    @MethodSource("mgetMatrix")
    void mgetMixedPresentMissing(String keys, String values) {
        TestCommandRunner runner = runner();
        String[] keyParts = keys.split(",");
        String[] valueParts = values.split(",");
        for (int i = 0; i < keyParts.length; i++) {
            if (!valueParts[i].equals("-")) {
                runner.exec("set", keyParts[i], valueParts[i]);
            }
        }
        RespValue result = runner.exec("mget", keyParts);
        List<RespValue> items = ((RespArray) result).values();
        assertThat(items).hasSize(keyParts.length);
        for (int i = 0; i < keyParts.length; i++) {
            if (valueParts[i].equals("-")) {
                assertThat(items.get(i)).isEqualTo(
                        RespNull.BULK_STRING);
            } else {
                assertThat(((RespBulkString) items.get(i)).bytes())
                        .isEqualTo(valueParts[i].getBytes(
                                StandardCharsets.UTF_8));
            }
        }
    }

    @ParameterizedTest(name = "mset size {0}")
    @MethodSource("msetSizes")
    void msetSizesRoundTrip(int size) {
        TestCommandRunner runner = runner();
        Object[] args = new Object[size * 2];
        String[] keys = new String[size];
        for (int i = 0; i < size; i++) {
            keys[i] = "key" + i;
            args[i * 2] = keys[i];
            args[i * 2 + 1] = "v" + i;
        }
        assertThat(runner.exec("mset", args)).isEqualTo(
                new RespSimpleString("OK"));
        RespValue result = runner.exec("mget", keys);
        assertThat(((RespArray) result).values()).hasSize(size);
        for (int i = 0; i < size; i++) {
            assertThat(((RespBulkString) ((RespArray) result)
                    .values().get(i)).bytes()).isEqualTo(
                    ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
    }

    @ParameterizedTest(name = "msetnx existing index {0}")
    @MethodSource("msetNxMatrix")
    void msetNxExistingIndex(int existingIndex) {
        TestCommandRunner runner = runner();
        Object[] args = new Object[]{"a", "1", "b", "2", "c", "3"};
        if (existingIndex >= 0) {
            runner.exec("set", "ab c".split(" ")[0]
                    .substring(0, 0) + ("abc").substring(
                    existingIndex, existingIndex + 1), "old");
        }
        RespValue result = runner.exec("msetnx", args);
        assertThat(((RespInteger) result).value())
                .isEqualTo(existingIndex >= 0 ? 0 : 1);
    }

    @ParameterizedTest(name = "del/exists {0}")
    @MethodSource("countMatrix")
    void delExistsCounts(String keys, String existing,
                         String expected) {
        TestCommandRunner runner = runner();
        for (String key : existing.split(",")) {
            runner.exec("set", key, "v");
        }
        String[] keyParts = keys.split(",");
        assertThat(runner.exec("del", keyParts)).isEqualTo(
                new RespInteger(Long.parseLong(expected)));
    }

    @ParameterizedTest(name = "wal multi-key {0}")
    @MethodSource("walMultiOps")
    void walMultiKeyOps(String op) throws Exception {
        Path dir = Files.createTempDirectory("wal-multi");
        WALManager wal = new WALManager(WALConfig.defaults(dir));
        TestCommandRunner runner = new TestCommandRunner(
                new WALStorageEngine(wal, MemTable.create()));
        switch (op) {
            case "mset" -> {
                runner.exec("mset", "a", "1", "b", "2");
                assertThat(runner.exec("exists", "a", "b"))
                        .isEqualTo(new RespInteger(2));
            }
            case "msetnx" -> {
                runner.exec("msetnx", "a", "1", "b", "2");
                assertThat(runner.exec("mget", "a", "b"))
                        .isInstanceOf(RespArray.class);
            }
            case "del" -> {
                runner.exec("mset", "a", "1", "b", "2");
                assertThat(runner.exec("del", "a", "b"))
                        .isEqualTo(new RespInteger(2));
            }
            default -> throw new AssertionError(op);
        }
        wal.close();
    }

    static Stream<Arguments> mgetMatrix() {
        return Stream.of(
                Arguments.of("a,b,c", "1,2,3"),
                Arguments.of("a,b,c", "1,-,3"),
                Arguments.of("a,b,c", "-,2,-"),
                Arguments.of("a,b,c", "-,-,-"),
                Arguments.of("x,y,z", "1,2,3"),
                Arguments.of("a,b", "1,-"),
                Arguments.of("a,b", "-,2"),
                Arguments.of("a,b", "1,2"),
                Arguments.of("a,a", "1,1"),
                Arguments.of("a,b,c,d", "1,2,3,4"),
                Arguments.of("k1,k2,k3", "-,-,-"),
                Arguments.of("k1,k2,k3", "v1,v2,v3"));
    }

    static Stream<Arguments> msetSizes() {
        return Stream.of(1, 2, 3, 5, 8).map(Arguments::of);
    }

    static Stream<Arguments> msetNxMatrix() {
        return Stream.of(-1, 0, 1, 2).map(Arguments::of);
    }

    static Stream<Arguments> countMatrix() {
        return Stream.of(
                Arguments.of("a,b,c", "a,b,c", "3"),
                Arguments.of("a,b,c", "a,b", "2"),
                Arguments.of("a,b,c", "a", "1"),
                Arguments.of("a,b,c", "", "0"),
                Arguments.of("x,y", "x,y,z", "2"),
                Arguments.of("a,a", "a", "1"));
    }

    static Stream<Arguments> walMultiOps() {
        return Stream.of("mset", "msetnx", "del")
                .map(Arguments::of);
    }
}
