package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespDouble;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 多模型值命令族（ADR-0320）：JSON.SET/GET、TS.*、VECTOR.SET/GET。 */
class MultiModelCommandTest {

    private final MemTable memTable = MemTable.create();

    private RespValue execute(String name, String... args) {
        return new MultiModelCommand(name).execute(
                List.of(args).stream()
                        .map(a -> a.getBytes(StandardCharsets.UTF_8))
                        .toList(),
                memTable);
    }

    @Test
    void jsonSetGetRoundTrip() {
        assertThat(execute("json.set", "k", "{\"a\":1}"))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue value = execute("json.get", "k");
        assertThat(value).isInstanceOf(RespBulkString.class);
        assertThat(new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8)).isEqualTo("{\"a\":1}");
    }

    @Test
    void jsonGetMissingReturnsNull() {
        assertThat(execute("json.get", "missing"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void jsonGetWrongTypeRejected() {
        execute("json.set", "k", "{}");
        assertThat(execute("vector.get", "k"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void tsAddAppendsPoints() {
        execute("ts.add", "sensor", "1000", "1.5");
        execute("ts.add", "sensor", "2000", "-2.25");
        assertThat(execute("ts.len", "sensor"))
                .isEqualTo(new RespInteger(2));

        RespValue resp = execute("ts.get", "sensor");
        assertThat(resp).isInstanceOf(RespArray.class);
        RespArray points = (RespArray) resp;
        assertThat(points.values()).hasSize(2);
        RespArray first = (RespArray) points.values().get(0);
        assertThat(((RespInteger) first.values().get(0)).value())
                .isEqualTo(1000);
        assertThat(((RespDouble) first.values().get(1)).value())
                .isEqualTo(1.5);
    }

    @Test
    void tsLenMissingReturnsZero() {
        assertThat(execute("ts.len", "missing"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void tsInvalidNumberRejected() {
        RespValue response = execute(
                "ts.add", "sensor", "abc", "1.0");
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(execute("ts.len", "sensor"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void vectorSetGetRoundTrip() {
        assertThat(execute("vector.set", "v", "3", "0.5", "-1", "2"))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue resp = execute("vector.get", "v");
        assertThat(resp).isInstanceOf(RespArray.class);
        RespArray values = (RespArray) resp;
        assertThat(values.values()).hasSize(3);
        assertThat(((RespDouble) values.values().get(0)).value())
                .isEqualTo(0.5);
        assertThat(((RespDouble) values.values().get(2)).value())
                .isEqualTo(2.0);
    }

    @Test
    void vectorGetMissingReturnsNullArray() {
        assertThat(execute("vector.get", "missing"))
                .isEqualTo(RespNull.ARRAY);
    }

    @Test
    void vectorSetDimMismatchRejected() {
        RespValue response = execute(
                "vector.set", "v", "3", "1", "2");
        assertThat(response).isInstanceOf(RespError.class);
    }

    @Test
    void wrongArityRejected() {
        assertThat(execute("json.set", "k"))
                .isInstanceOf(RespError.class);
        assertThat(execute("ts.add", "sensor", "1"))
                .isInstanceOf(RespError.class);
        assertThat(execute("vector.set", "v"))
                .isInstanceOf(RespError.class);
    }
}
