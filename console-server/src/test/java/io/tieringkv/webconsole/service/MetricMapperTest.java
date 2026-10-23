package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.support.InfoParser;
import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

class MetricMapperTest {

    private static final String INFO = """
            # Server
            redis_version:4.1.0
            uptime_in_seconds:600
            # Clients
            connected_clients:3
            active_requests:1
            # Memory
            used_memory:512
            maxmemory:1024
            # Stats
            qps:88.0
            avg_latency_ms:0.125
            instantaneous_ops_per_sec:90.0
            errors:2
            # Keyspace
            db0:keys=9,expires=0,avg_ttl=0
            """;

    private static MetricPoint point() {
        return MetricMapper.toPoint(1L, InfoParser.parse(INFO), LocalDateTime.now());
    }

    @Test
    void mapsInfoIntoMetricPoint() {
        MetricPoint point = point();
        assertThat(point.getQps()).isEqualTo(88.0);
        assertThat(point.getConnections()).isEqualTo(3);
        assertThat(point.getUsedMemory()).isEqualTo(512);
        assertThat(point.getTotalKeys()).isEqualTo(9);
        assertThat(point.getUptimeSeconds()).isEqualTo(600);
    }

    @Test
    void buildsRuleDictionaryIncludingMemoryRatio() {
        Map<String, Double> metrics = MetricMapper.metricDictionary(point());
        assertThat(metrics).containsEntry("used_memory_ratio", 0.5)
                .containsEntry("connections", 3.0)
                .containsEntry("total_keys", 9.0);
    }

    @Test
    void ratioIsZeroWhenMaxMemoryUnknown() {
        MetricPoint point = new MetricPoint();
        point.setUsedMemory(100L);
        point.setMaxMemory(0L);
        assertThat(MetricMapper.metricDictionary(point))
                .containsEntry("used_memory_ratio", 0.0);
    }
}
