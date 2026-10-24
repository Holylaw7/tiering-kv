package io.tieringkv.webconsole.resp;

import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class RespCodecTest {

    private static RespReply parse(String raw) throws IOException {
        return RespCodec.read(new ByteArrayInputStream(raw.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void encodesCommandAsBulkArray() {
        byte[] encoded = RespCodec.encodeCommand("SET", "k", "v");
        assertThat(new String(encoded, StandardCharsets.UTF_8))
                .isEqualTo("*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n");
    }

    @Test
    void parsesSimpleString() throws IOException {
        assertThat(parse("+OK\r\n")).isEqualTo(new RespReply.Simple("OK"));
    }

    @Test
    void parsesError() throws IOException {
        assertThat(parse("-ERR unknown command\r\n"))
                .isEqualTo(new RespReply.Error("ERR unknown command"));
    }

    @Test
    void parsesInteger() throws IOException {
        assertThat(parse(":42\r\n")).isEqualTo(new RespReply.Integer(42));
    }

    @Test
    void parsesBulkString() throws IOException {
        RespReply reply = parse("$5\r\nhello\r\n");
        assertThat(reply).isInstanceOf(RespReply.Bulk.class);
        assertThat(((RespReply.Bulk) reply).utf8()).isEqualTo("hello");
    }

    @Test
    void treatsNegativeBulkLengthAsNil() throws IOException {
        RespReply reply = parse("$-1\r\n");
        assertThat(((RespReply.Bulk) reply).isNil()).isTrue();
    }

    @Test
    void parsesNestedArray() throws IOException {
        RespReply reply = parse("*2\r\n$1\r\na\r\n*2\r\n:1\r\n+OK\r\n");
        assertThat(reply).isInstanceOf(RespReply.Array.class);
        List<RespReply> items = ((RespReply.Array) reply).items();
        assertThat(items).hasSize(2);
        assertThat(((RespReply.Bulk) items.get(0)).utf8()).isEqualTo("a");
        assertThat(((RespReply.Array) items.get(1)).items()).hasSize(2);
    }

    @Test
    void rejectsUnknownPrefix() {
        assertThatThrownBy(() -> parse("?oops\r\n"))
                .isInstanceOf(RespException.class);
    }

    @Test
    void rejectsTruncatedBulk() {
        assertThatThrownBy(() -> parse("$5\r\nab\r\n"))
                .isInstanceOf(IOException.class);
    }

    @Test
    void rejectsMalformedInteger() {
        assertThatThrownBy(() -> parse(":abc\r\n"))
                .isInstanceOf(RespException.class);
    }
}
