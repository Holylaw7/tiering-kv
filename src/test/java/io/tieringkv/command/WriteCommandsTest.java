package io.tieringkv.command;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class WriteCommandsTest {

    @Test
    void detectsCoreWriteCommands() {
        assertThat(WriteCommands.isWrite("set")).isTrue();
        assertThat(WriteCommands.isWrite("getset")).isTrue();
        assertThat(WriteCommands.isWrite("hset")).isTrue();
        assertThat(WriteCommands.isWrite("del")).isTrue();
        assertThat(WriteCommands.isWrite("expire")).isTrue();
        assertThat(WriteCommands.isWrite("flushall")).isTrue();
    }

    @Test
    void detectsMultimodelWrites() {
        assertThat(WriteCommands.isWrite("JSON.SET")).isTrue();
        assertThat(WriteCommands.isWrite("ts.add")).isTrue();
        assertThat(WriteCommands.isWrite("vector.add")).isTrue();
    }

    @Test
    void readCommandsAreNotWrites() {
        assertThat(WriteCommands.isWrite("get")).isFalse();
        assertThat(WriteCommands.isWrite("mget")).isFalse();
        assertThat(WriteCommands.isWrite("lrange")).isFalse();
        assertThat(WriteCommands.isWrite("smembers")).isFalse();
        assertThat(WriteCommands.isWrite("zrange")).isFalse();
        assertThat(WriteCommands.isWrite("scan")).isFalse();
        assertThat(WriteCommands.isWrite("info")).isFalse();
        assertThat(WriteCommands.isWrite("ttl")).isFalse();
        assertThat(WriteCommands.isWrite(null)).isFalse();
    }

    @Test
    void freeingWritesAreAllowedUnderPressure() {
        assertThat(WriteCommands.isRejectedUnderPressure("del")).isFalse();
        assertThat(WriteCommands.isRejectedUnderPressure("flushall")).isFalse();
        assertThat(WriteCommands.isRejectedUnderPressure("expire")).isFalse();
        assertThat(WriteCommands.isRejectedUnderPressure("hdel")).isFalse();
        assertThat(WriteCommands.isRejectedUnderPressure("get")).isFalse();
    }

    @Test
    void allocatingWritesAreRejectedUnderPressure() {
        assertThat(WriteCommands.isRejectedUnderPressure("set")).isTrue();
        assertThat(WriteCommands.isRejectedUnderPressure("lpush")).isTrue();
        assertThat(WriteCommands.isRejectedUnderPressure("sadd")).isTrue();
        assertThat(WriteCommands.isRejectedUnderPressure("JSON.SET")).isTrue();
    }

    @Test
    void isCaseInsensitive() {
        assertThat(WriteCommands.isWrite("SeT")).isTrue();
        assertThat(WriteCommands.isWrite("HGETALL")).isFalse();
    }
}
