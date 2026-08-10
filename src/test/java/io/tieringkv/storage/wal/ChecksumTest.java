package io.tieringkv.storage.wal;

import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;

class ChecksumTest {

    @Test
    void crc32cKnownVector() {
        // 标准 CRC-32C 校验值：crc32c("123456789") = 0xE3069283
        assertThat(ChecksumValidator.crc32c(
                "123456789".getBytes(StandardCharsets.US_ASCII), 9)).isEqualTo(0xE3069283L);
    }

    @Test
    void detectsSingleBitFlip() {
        byte[] data = "hello world".getBytes(StandardCharsets.UTF_8);
        long original = ChecksumValidator.crc32c(data, data.length);
        data[5] ^= 0x01;
        assertThat(ChecksumValidator.matches(data, data.length, original)).isFalse();
    }
}
