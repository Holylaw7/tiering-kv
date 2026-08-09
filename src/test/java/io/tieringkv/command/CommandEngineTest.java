package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

class CommandEngineTest {

    private CommandEngine engine;
    private KVStore store;

    @BeforeEach
    void setUp() {
        store = new InMemoryKVStore();
        engine = new CommandEngine(CommandRegistry.createDefault(), store);
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

    private RespValue execute(String name, String... args) {
        List<byte[]> argBytes = new ArrayList<>(args.length);
        for (String arg : args) {
            argBytes.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return engine.execute(new RespCommand(name, argBytes));
    }
}
