package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespSimpleString;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespRequestParserTest {

    @Test
    void parsesArrayRequestAndLowercasesName() {
        RespArray array = new RespArray(List.of(
                new RespBulkString("SeT".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("k".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("v".getBytes(StandardCharsets.UTF_8))));
        RespCommand command = RespRequestParser.parse(array);
        assertThat(command.name()).isEqualTo("set");
        assertThat(command.args()).hasSize(2);
    }

    @Test
    void rejectsNonArray() {
        assertThatThrownBy(() -> RespRequestParser.parse(new RespSimpleString("PING")))
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsEmptyArray() {
        assertThatThrownBy(() -> RespRequestParser.parse(new RespArray(List.of())))
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsNonBulkHead() {
        assertThatThrownBy(() -> RespRequestParser.parse(new RespArray(List.of(new RespInteger(1)))))
                .isInstanceOf(RespProtocolException.class);
    }

    @Test
    void rejectsNonBulkArgument() {
        assertThatThrownBy(() -> RespRequestParser.parse(new RespArray(List.of(
                new RespBulkString("SET".getBytes(StandardCharsets.UTF_8)),
                new RespInteger(1)))))
                .isInstanceOf(RespProtocolException.class);
    }
}
