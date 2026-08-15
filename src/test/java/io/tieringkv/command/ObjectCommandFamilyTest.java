package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** OBJECT 命令族（ADR-0340）：ENCODING/REFCOUNT/IDLETIME/FREQ。 */
class ObjectCommandFamilyTest {

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create(),
                CommandRegistry.createDefaultWithVector(
                        () -> "# Server\r\nno metrics\r\n",
                        Map.of(), new VectorIndexStore(4)));
    }

    private static String encoding(TestCommandRunner runner,
                                   String key) {
        RespValue value = runner.exec("object", "encoding", key);
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    @Test
    void stringEncodingShortEmbstrLongRaw() {
        TestCommandRunner runner = runner();
        runner.exec("set", "short", "hello");
        assertThat(encoding(runner, "short")).isEqualTo("embstr");
        runner.exec("set", "long", "x".repeat(100));
        assertThat(encoding(runner, "long")).isEqualTo("raw");
    }

    @Test
    void compositeTypeEncodings() {
        TestCommandRunner runner = runner();
        runner.exec("hset", "h", "f", "1");
        assertThat(encoding(runner, "h")).isEqualTo("hashtable");
        runner.exec("rpush", "l", "a");
        assertThat(encoding(runner, "l")).isEqualTo("quicklist");
        runner.exec("sadd", "s", "a");
        assertThat(encoding(runner, "s")).isEqualTo("hashtable");
        runner.exec("zadd", "z", "1", "a");
        assertThat(encoding(runner, "z")).isEqualTo("skiplist");
        runner.exec("xadd", "st", "*", "f", "v");
        assertThat(encoding(runner, "st")).isEqualTo("stream");
    }

    @Test
    void multiModelTypeEncodings() {
        TestCommandRunner runner = runner();
        runner.exec("json.set", "j", "{\"a\":1}");
        assertThat(encoding(runner, "j")).isEqualTo("json");
        runner.exec("ts.add", "t", "1", "1");
        assertThat(encoding(runner, "t")).isEqualTo("timeseries");
        runner.exec("vector.set", "v", "2", "1", "0");
        assertThat(encoding(runner, "v")).isEqualTo("vector");
    }

    @Test
    void missingKeyReturnsNull() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("object", "encoding", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
        assertThat(runner.exec("object", "refcount", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
        assertThat(runner.exec("object", "idletime", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
        assertThat(runner.exec("object", "freq", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void refcountIdletimeFreq() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(((RespInteger) runner.exec(
                "object", "refcount", "k")).value()).isEqualTo(1);
        assertThat(((RespInteger) runner.exec(
                "object", "idletime", "k")).value()).isZero();
        assertThat(((RespInteger) runner.exec(
                "object", "freq", "k")).value()).isEqualTo(-1);
    }

    @Test
    void unknownSubcommandAndArityRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "v");
        assertThat(runner.exec("object", "bogus", "k"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("object"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("object", "encoding"))
                .isInstanceOf(RespError.class);
    }
}
