package io.tieringkv.webconsole.support;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class TextTest {

    @Test
    void decodesValidUtf8() {
        assertThat(Text.decode("中文值".getBytes(StandardCharsets.UTF_8))).isEqualTo("中文值");
    }

    @Test
    void fallsBackToBase64ForBinary() {
        byte[] invalid = {(byte) 0xff, (byte) 0xfe, 0x00};
        assertThat(Text.decode(invalid)).startsWith(Text.BASE64_PREFIX);
    }

    @Test
    void nullStaysNull() {
        assertThat(Text.decode(null)).isNull();
    }
}
