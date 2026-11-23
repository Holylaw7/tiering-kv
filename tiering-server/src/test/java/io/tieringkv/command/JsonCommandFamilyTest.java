package io.tieringkv.command;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** JSON 路径命令族（ADR-0336）：RedisJSON 风格路径读写与变更。 */
class JsonCommandFamilyTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private TestCommandRunner runner() {
        return new TestCommandRunner(MemTable.create(),
                CommandRegistry.createDefaultWithVector(
                        () -> "# Server\r\nno metrics\r\n",
                        Map.of(), new VectorIndexStore(4)));
    }

    private static long integer(RespValue value) {
        return ((RespInteger) value).value();
    }

    private static String text(RespValue value) {
        return new String(((RespBulkString) value).bytes(),
                StandardCharsets.UTF_8);
    }

    private static void setStore(TestCommandRunner runner) {
        runner.exec("json.set", "k", "$", STORE);
    }

    private static final String STORE = "{"
            + "\"store\":{"
            + "\"book\":["
            + "{\"category\":\"reference\",\"author\":\"Nigel Rees\","
            + "\"title\":\"Sayings of the Century\",\"price\":8.95},"
            + "{\"category\":\"fiction\",\"author\":\"Evelyn Waugh\","
            + "\"title\":\"Sword of Honour\",\"price\":12.99},"
            + "{\"category\":\"fiction\",\"author\":\"Herman Melville\","
            + "\"title\":\"Moby Dick\",\"isbn\":\"0-553-21311-3\","
            + "\"price\":8.99}"
            + "],"
            + "\"bicycle\":{\"color\":\"red\",\"price\":19.95}"
            + "}"
            + "}";

    @Test
    void jsonSetRootAndGetRaw() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.set", "k", "{\"a\":1}"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(text(runner.exec("json.get", "k")))
                .isEqualTo("{\"a\":1}");
        assertThat(runner.exec("json.set", "k", "$", "{\"b\":2}"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(text(runner.exec("json.get", "k")))
                .isEqualTo("{\"b\":2}");
    }

    @Test
    void jsonSetLegacyPathCreatesNested() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.set", "k", ".a.b", "1"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(text(runner.exec("json.get", "k", ".a.b")))
                .isEqualTo("1");
        assertThat(text(runner.exec("json.get", "k", "$..b")))
                .isEqualTo("[1]");
    }

    @Test
    void jsonSetOverwritesExisting() {
        TestCommandRunner runner = runner();
        setStore(runner);
        runner.exec("json.set", "k", ".store.bicycle.color", "\"blue\"");
        assertThat(text(runner.exec("json.get", "k",
                ".store.bicycle.color"))).isEqualTo("\"blue\"");
    }

    @Test
    void jsonSetNxXx() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.set", "k", "$", "{}", "nx"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(runner.exec("json.set", "k", "$", "{}", "nx"))
                .isEqualTo(RespNull.BULK_STRING);
        assertThat(runner.exec("json.set", "k", "$", "{}", "xx"))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(runner.exec("json.set", "nope", "$", "{}", "xx"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void jsonSetInvalidJsonRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.set", "k", "$", "{"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("exists", "k"))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void jsonSetWrongTypeRejected() {
        TestCommandRunner runner = runner();
        runner.exec("set", "k", "plain");
        assertThat(runner.exec("json.set", "k", "$", "{}"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void jsonSetPreservesTtl() {
        TestCommandRunner runner = runner();
        runner.exec("json.set", "k", "$", "{}");
        runner.exec("expire", "k", "100");
        runner.exec("json.set", "k", ".x", "1");
        RespValue ttl = runner.exec("ttl", "k");
        assertThat(integer(ttl)).isBetween(1L, 100L);
    }

    @Test
    void jsonGetLegacySingleValue() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(text(runner.exec("json.get", "k",
                ".store.bicycle.color"))).isEqualTo("\"red\"");
        assertThat(text(runner.exec("json.get", "k",
                ".store.book[0].title")))
                .isEqualTo("\"Sayings of the Century\"");
        assertThat(runner.exec("json.get", "k", ".nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void jsonGetJsonPathReturnsArray() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(text(runner.exec("json.get", "k",
                "$.store.book[0].title")))
                .isEqualTo("[\"Sayings of the Century\"]");
        assertThat(text(runner.exec("json.get", "k", "$.nope")))
                .isEqualTo("[]");
    }

    @Test
    void jsonGetRecursiveDescent() {
        TestCommandRunner runner = runner();
        setStore(runner);
        JsonNode prices = parse(text(runner.exec("json.get", "k",
                "$..price")));
        assertThat(prices.isArray()).isTrue();
        assertThat(prices.size()).isEqualTo(4);
        assertThat(prices.get(0).asDouble()).isEqualTo(8.95);
        assertThat(prices.get(3).asDouble()).isEqualTo(19.95);
    }

    @Test
    void jsonGetMultiplePathsReturnsObject() throws Exception {
        TestCommandRunner runner = runner();
        setStore(runner);
        String body = text(runner.exec("json.get", "k",
                ".store.bicycle.color", "$.store.bicycle.price"));
        JsonNode result = MAPPER.readTree(body);
        assertThat(result.isObject()).isTrue();
        assertThat(result.size()).isEqualTo(2);
        assertThat(result.get(".store.bicycle.color").asText())
                .isEqualTo("red");
        assertThat(result.get("$.store.bicycle.price").get(0)
                .asDouble()).isEqualTo(19.95);
    }

    @Test
    void jsonDelNestedAndRoot() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(integer(runner.exec("json.del", "k",
                ".store.bicycle.color"))).isEqualTo(1);
        assertThat(runner.exec("json.get", "k",
                ".store.bicycle.color"))
                .isEqualTo(RespNull.BULK_STRING);
        assertThat(integer(runner.exec("json.del", "k", "$")))
                .isEqualTo(1);
        assertThat(integer(runner.exec("exists", "k"))).isZero();
        assertThat(integer(runner.exec("json.del", "k", "$")))
                .isZero();
    }

    @Test
    void jsonDelArrayElement() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(integer(runner.exec("json.del", "k",
                ".store.book[0]"))).isEqualTo(1);
        assertThat(integer(runner.exec("json.arrlen", "k",
                ".store.book"))).isEqualTo(2);
    }

    @Test
    void jsonTypeSingleAndMultiple() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(runner.exec("json.type", "k",
                ".store.bicycle")).isEqualTo(
                new RespBulkString("object".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("json.type", "k", ".store.book"))
                .isEqualTo(new RespBulkString("array".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("json.type", "k",
                ".store.book[0].price"))
                .isEqualTo(new RespBulkString("number".getBytes(
                        StandardCharsets.UTF_8)));
        RespArray types = (RespArray) runner.exec("json.type", "k",
                "$..price");
        assertThat(types.values()).hasSize(4);
        for (RespValue type : types.values()) {
            assertThat(text(type)).isEqualTo("number");
        }
    }

    @Test
    void jsonArrAppendAndArrLen() {
        TestCommandRunner runner = runner();
        setStore(runner);
        runner.exec("json.set", "k", ".store.book[0].tags", "[]");
        assertThat(integer(runner.exec("json.arrappend", "k",
                ".store.book[0].tags", "\"redis\"", "\"json\"")))
                .isEqualTo(2);
        assertThat(integer(runner.exec("json.arrlen", "k",
                ".store.book[0].tags"))).isEqualTo(2);
        assertThat(text(runner.exec("json.get", "k",
                ".store.book[0].tags")))
                .isEqualTo("[\"redis\",\"json\"]");
    }

    @Test
    void jsonArrAppendWrongTypeRejected() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(runner.exec("json.arrappend", "k",
                ".store.bicycle", "\"x\""))
                .isInstanceOf(RespError.class);
    }

    @Test
    void jsonObjLenAndObjKeys() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(integer(runner.exec("json.objlen", "k",
                ".store.bicycle"))).isEqualTo(2);
        RespArray keys = (RespArray) runner.exec("json.objkeys", "k",
                ".store.bicycle");
        assertThat(keys.values()).hasSize(2);
        assertThat(text(keys.values().get(0))).isEqualTo("color");
        assertThat(text(keys.values().get(1))).isEqualTo("price");
    }

    @Test
    void jsonStrLen() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(integer(runner.exec("json.strlen", "k",
                ".store.bicycle.color"))).isEqualTo(3);
    }

    @Test
    void jsonNumIncrBy() {
        TestCommandRunner runner = runner();
        setStore(runner);
        assertThat(text(runner.exec("json.numincrby", "k",
                ".store.bicycle.price", "5"))).isEqualTo("24.95");
        assertThat(text(runner.exec("json.numincrby", "k",
                ".store.book[0].price", "-0.95"))).isEqualTo("8");
        assertThat(runner.exec("json.numincrby", "k",
                ".store.bicycle.color", "1"))
                .isInstanceOf(RespError.class);
        assertThat(runner.exec("json.numincrby", "k", ".nope", "1"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void jsonWildcardReads() {
        TestCommandRunner runner = runner();
        setStore(runner);
        JsonNode authors = parse(text(runner.exec("json.get", "k",
                "$.store.book[*].author")));
        assertThat(authors.size()).isEqualTo(3);
        assertThat(authors.get(1).asText()).isEqualTo("Evelyn Waugh");
        JsonNode prices = parse(text(runner.exec("json.get", "k",
                "$.store.*.price")));
        assertThat(prices.size()).isEqualTo(1);
        assertThat(prices.get(0).asDouble()).isEqualTo(19.95);
    }

    @Test
    void jsonWrongArityRejected() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.get")).isInstanceOf(RespError.class);
        assertThat(runner.exec("json.del")).isInstanceOf(RespError.class);
        assertThat(runner.exec("json.numincrby", "k", ".a"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void jsonGetMissingKeyReturnsNull() {
        TestCommandRunner runner = runner();
        assertThat(runner.exec("json.get", "nope"))
                .isEqualTo(RespNull.BULK_STRING);
    }

    private static JsonNode parse(String json) {
        try {
            return MAPPER.readTree(json);
        } catch (Exception e) {
            throw new AssertionError("invalid json response: " + json, e);
        }
    }
}
