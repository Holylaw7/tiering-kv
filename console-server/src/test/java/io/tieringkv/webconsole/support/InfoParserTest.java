package io.tieringkv.webconsole.support;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class InfoParserTest {

    private static final String INFO = """
            # Server
            redis_version:4.1.0
            tcp_port:6379
            uptime_in_seconds:120

            # Memory
            used_memory:2048
            maxmemory:1048576

            # Stats
            qps:12.5
            avg_latency_ms:0.250

            # Keyspace
            db0:keys=7,expires=2,avg_ttl=0
            """;

    @Test
    void parsesSectionsAndValues() {
        Map<String, Map<String, String>> sections = InfoParser.parse(INFO);
        assertThat(sections).containsKeys("Server", "Memory", "Stats", "Keyspace");
        assertThat(InfoParser.get(sections, "Server", "redis_version")).isEqualTo("4.1.0");
        assertThat(InfoParser.get(sections, "Memory", "maxmemory")).isEqualTo("1048576");
    }

    @Test
    void returnsTypedNumbersWithFallback() {
        Map<String, Map<String, String>> sections = InfoParser.parse(INFO);
        assertThat(InfoParser.longValue(sections, "Memory", "used_memory", 0)).isEqualTo(2048);
        assertThat(InfoParser.doubleValue(sections, "Stats", "qps", 0)).isEqualTo(12.5);
        assertThat(InfoParser.longValue(sections, "Memory", "missing", -1)).isEqualTo(-1);
    }

    @Test
    void sumsKeyspaceKeyCount() {
        assertThat(InfoParser.keyCount(InfoParser.parse(INFO))).isEqualTo(7);
    }

    @Test
    void emptyInputYieldsEmptySections() {
        assertThat(InfoParser.parse("")).isEmpty();
        assertThat(InfoParser.parse(null)).isEmpty();
        assertThat(InfoParser.keyCount(InfoParser.parse(""))).isZero();
    }

    @Test
    void flattenKeepsFirstOccurrence() {
        Map<String, String> flat = InfoParser.flatten(InfoParser.parse(INFO));
        assertThat(flat).containsEntry("redis_version", "4.1.0")
                .containsEntry("qps", "12.5");
    }
}
