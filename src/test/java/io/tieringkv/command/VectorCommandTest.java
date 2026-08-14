package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 向量命令族（ADR-0319）：VECTOR.ADD / SEARCH / DEL / LEN。 */
class VectorCommandTest {

    private final VectorIndexStore store = new VectorIndexStore(4);
    private final MemTable memTable = MemTable.create();

    private RespValue execute(String name, String... args) {
        Command command = switch (name) {
            case "vector.add" -> new VectorCommand("vector.add", store);
            case "vector.search" ->
                    new VectorCommand("vector.search", store);
            case "vector.del" -> new VectorCommand("vector.del", store);
            case "vector.len" -> new VectorCommand("vector.len", store);
            default -> throw new IllegalArgumentException(name);
        };
        return command.execute(
                List.of(args).stream()
                        .map(a -> a.getBytes(StandardCharsets.UTF_8))
                        .toList(),
                memTable);
    }

    @Test
    void addSearchDelLenRoundTrip() {
        assertThat(execute("vector.add", "doc-1", "2", "1", "0"))
                .isEqualTo(new RespSimpleString("OK"));
        execute("vector.add", "doc-2", "2", "0", "1");
        assertThat(execute("vector.len"))
                .isEqualTo(new RespInteger(2));

        RespValue search = execute(
                "vector.search", "2", "1", "0", "TOPK", "1");
        assertThat(search).isInstanceOf(RespArray.class);
        RespArray results = (RespArray) search;
        assertThat(results.values()).hasSize(1);
        RespArray top = (RespArray) results.values().get(0);
        assertThat(new String(((RespBulkString)
                top.values().get(0)).bytes(),
                StandardCharsets.UTF_8)).isEqualTo("doc-1");

        assertThat(execute("vector.del", "doc-1"))
                .isEqualTo(new RespInteger(1));
        assertThat(execute("vector.del", "doc-1"))
                .isEqualTo(new RespInteger(0));
        assertThat(execute("vector.len"))
                .isEqualTo(new RespInteger(1));
    }

    @Test
    void wrongArityRejected() {
        assertThat(execute("vector.add", "2"))
                .isInstanceOf(RespError.class);
        assertThat(execute("vector.add", "x", "1"))
                .isInstanceOf(RespError.class);
        assertThat(execute("vector.search", "2"))
                .isInstanceOf(RespError.class);
        assertThat(execute("vector.del", "a", "b"))
                .isInstanceOf(RespError.class);
        assertThat(execute("vector.len", "x"))
                .isInstanceOf(RespError.class);
    }

    @Test
    void invalidFloatRejected() {
        RespValue response = execute(
                "vector.add", "doc", "2", "abc", "0");
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(store.size()).isZero();
    }

    @Test
    void negativeTopKRejected() {
        execute("vector.add", "doc", "2", "1", "0");
        assertThat(execute("vector.search", "2", "1", "0",
                "TOPK", "-1")).isInstanceOf(RespError.class);
    }

    @Test
    void registryWithVectorExposesFourCommands() {
        CommandRegistry registry = CommandRegistry
                .createDefaultWithVector(() -> "info",
                        java.util.Map.of(), store);
        assertThat(registry.find("vector.add")).isNotNull();
        assertThat(registry.find("vector.search")).isNotNull();
        assertThat(registry.find("vector.del")).isNotNull();
        assertThat(registry.find("vector.len")).isNotNull();
    }

    @Test
    void defaultRegistryUnchangedWithoutVector() {
        CommandRegistry registry = CommandRegistry.createDefault();
        assertThat(registry.find("vector.add")).isNull();
        // 既有 115 命令断言保持不变（Phase51-56 基线）
        assertThat(registry.size()).isEqualTo(115);
    }
}
