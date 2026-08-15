package io.tieringkv.runtime;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.StringReader;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 网关 RESP2 解析/编码（ADR-0343 真实 Runner 修正）：RealNetworkChaosTest
 * 的 RespClient 发送 RESP 数组，网关必须按 RESP 解析而非行解析。
 */
class GatewayRuntimeRespTest {

    @Test
    void parseSetArray() throws Exception {
        String wire = "*3\r\n$3\r\nSET\r\n$1\r\nk\r\n$1\r\nv\r\n";
        String[] parts = GatewayRuntime.parseCommand(
                new BufferedReader(new StringReader(wire)));
        assertThat(parts).containsExactly("SET", "k", "v");
    }

    @Test
    void parseGetArray() throws Exception {
        String wire = "*2\r\n$3\r\nGET\r\n$1\r\nk\r\n";
        String[] parts = GatewayRuntime.parseCommand(
                new BufferedReader(new StringReader(wire)));
        assertThat(parts).containsExactly("GET", "k");
    }

    @Test
    void parseReturnsNullOnEof() throws Exception {
        assertThat(GatewayRuntime.parseCommand(
                new BufferedReader(new StringReader("")))).isNull();
    }

    @Test
    void parseRejectsNonArrayCommand() {
        assertThatThrownBy(() -> GatewayRuntime.parseCommand(
                new BufferedReader(new StringReader("+PONG\r\n"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("RESP array");
    }

    @Test
    void parseRejectsTruncatedBulk() {
        assertThatThrownBy(() -> GatewayRuntime.parseCommand(
                new BufferedReader(new StringReader("*1\r\n$5\r\nabc"))))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("truncated");
    }

    @Test
    void encodeSimpleAndError() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GatewayRuntime.writeSimple(out, "OK");
        GatewayRuntime.writeError(out, "ERR boom");
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("+OK\r\n-ERR boom\r\n");
    }

    @Test
    void encodeBulkAndNull() throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        GatewayRuntime.writeBulk(out, "v1".getBytes(StandardCharsets.UTF_8));
        GatewayRuntime.writeNullBulk(out);
        assertThat(out.toString(StandardCharsets.UTF_8))
                .isEqualTo("$2\r\nv1\r\n$-1\r\n");
    }
}
