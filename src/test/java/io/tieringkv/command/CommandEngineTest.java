package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandEngineTest {

    private CommandEngine engine;
    private StorageEngine storage;
    private MutableClock clock;

    @BeforeEach
    void setUp() {
        clock = new MutableClock(0);
        storage = MemTable.createForTest(clock, new MemoryManager(1 << 30));
        engine = new CommandEngine(CommandRegistry.createDefault(), storage);
    }

    @Test
    void pingWithoutArgsReturnsPong() {
        assertThat(execute("ping")).isEqualTo(new RespSimpleString("PONG"));
    }

    @Test
    void pingWithMessageEchoesBulk() {
        assertThat(execute("ping", "hello")).isEqualTo(new RespBulkString("hello".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void pingWithTooManyArgsReturnsArityError() {
        assertThat(execute("ping", "a", "b"))
                .isEqualTo(RespError.wrongArity("ping"));
    }

    @Test
    void echoReturnsArgument() {
        assertThat(execute("echo", "abc")).isEqualTo(new RespBulkString("abc".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void setGetRoundTrip() {
        assertThat(execute("set", "key", "value")).isEqualTo(new RespSimpleString("OK"));
        assertThat(execute("get", "key")).isEqualTo(new RespBulkString("value".getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void getMissingReturnsNullBulk() {
        assertThat(execute("get", "missing")).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void delReturnsRemovedCount() {
        execute("set", "a", "1");
        execute("set", "b", "2");
        assertThat(execute("del", "a", "b", "c")).isEqualTo(new RespInteger(2));
        assertThat(execute("get", "a")).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void existsReturnsExistingCount() {
        execute("set", "a", "1");
        assertThat(execute("exists", "a", "b", "a")).isEqualTo(new RespInteger(2));
    }

    @Test
    void unknownCommandReturnsError() {
        assertThat(execute("foo")).isEqualTo(RespError.unknownCommand("foo"));
    }

    @Test
    void commandNamesAreCaseInsensitive() {
        assertThat(execute("GeT", "key")).isEqualTo(RespNull.BULK_STRING);
        assertThat(execute("SET", "key", "v")).isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void binaryKeysAndValuesAreSafe() {
        byte[] key = new byte[]{'k', '\r', '\n', 0};
        byte[] value = new byte[]{'v', '\r', '\n', 1};
        assertThat(engine.execute(new RespCommand("set", List.of(key, value))))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue result = engine.execute(new RespCommand("get", List.of(key)));
        assertThat(result).isInstanceOf(RespBulkString.class);
        assertThat(((RespBulkString) result).bytes()).isEqualTo(value);
    }

    @Test
    void setWithExSecondsExpires() {
        assertThat(execute("set", "k", "v", "ex", "1")).isEqualTo(new RespSimpleString("OK"));
        assertThat(execute("get", "k"))
                .isEqualTo(new RespBulkString("v".getBytes(StandardCharsets.UTF_8)));
        clock.advance(1001);
        assertThat(execute("get", "k")).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void setWithPxMillisecondsExpires() {
        assertThat(execute("set", "k", "v", "px", "500")).isEqualTo(new RespSimpleString("OK"));
        clock.advance(501);
        assertThat(execute("get", "k")).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void setWithInvalidTtlReturnsIntegerError() {
        assertThat(execute("set", "k", "v", "ex", "abc"))
                .isEqualTo(new RespError("ERR value is not an integer or out of range"));
    }

    @Test
    void setWithUnknownOptionReturnsSyntaxError() {
        assertThat(execute("set", "k", "v", "xx", "1"))
                .isEqualTo(new RespError("ERR syntax error"));
    }

    @Test
    void setWithTooManyArgsReturnsArityError() {
        assertThat(execute("set", "k", "v", "ex", "1", "extra"))
                .isEqualTo(RespError.wrongArity("set"));
    }

    private RespValue execute(String name, String... args) {
        List<byte[]> argBytes = new ArrayList<>(args.length);
        for (String arg : args) {
            argBytes.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return engine.execute(new RespCommand(name, argBytes));
    }
}
