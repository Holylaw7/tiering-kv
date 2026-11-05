package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.web.dto.LiveMetrics;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Phase 7 契约：INFO 是否上报分层指标，决定控制台是"零值"还是"未上报"。 */
class MonitorServiceLiveTest {

    private RespGateway gateway;
    private MonitorService service;
    private DbInstance instance;

    @BeforeEach
    void setUp() {
        gateway = mock(RespGateway.class);
        service = new MonitorService(mock(InstanceService.class), gateway,
                mock(io.tieringkv.webconsole.repository.MetricPointRepository.class),
                mock(io.tieringkv.webconsole.repository.DbInstanceRepository.class),
                mock(AlertService.class));
        instance = new DbInstance();
        instance.setId(1L);
    }

    @Test
    void parsesTieringSectionAndMarksReported() {
        when(gateway.info(instance)).thenReturn("""
                # Stats
                qps:12.5
                avg_latency_ms:0.750
                latency_p50_ms:0.500
                latency_p95_ms:2.500
                latency_p99_ms:10.000
                latency_max_ms:42
                latency_samples:100
                total_keys:7
                # Memory
                used_memory:1024
                maxmemory:4096
                # Tiering
                tiering_memtable_used_bytes:1024
                tiering_memtable_max_bytes:4096
                tiering_memtable_entries:7
                tiering_cold_sstable_count:2
                tiering_cold_disk_usage_bytes:8192
                tiering_flush_count:3
                tiering_compaction_count:1
                tiering_compaction_input_bytes:100
                tiering_compaction_output_bytes:50
                tiering_compaction_latency_ms:12.500
                tiering_block_cache_hits:9
                tiering_block_cache_misses:1
                tiering_block_cache_hit_rate:0.9000
                tiering_flush_in_progress:1
                tiering_compaction_in_progress:0
                """);

        LiveMetrics live = service.live(instance).orElseThrow();

        assertThat(live.tieringReported()).isTrue();
        assertThat(live.memtableEntries()).isEqualTo(7);
        assertThat(live.coldSstableCount()).isEqualTo(2);
        assertThat(live.compactionCount()).isEqualTo(1);
        assertThat(live.flushInProgress()).isEqualTo(1);
        assertThat(live.cacheHitRate()).isEqualTo(0.9);
        assertThat(live.p95Millis()).isEqualTo(2.5);
        assertThat(live.latencyMaxMillis()).isEqualTo(42);
    }

    @Test
    void missingTieringSectionIsReportedAsNotSupported() {
        when(gateway.info(instance)).thenReturn("""
                # Stats
                qps:1.0
                total_keys:3
                # Memory
                used_memory:100
                maxmemory:200
                """);

        LiveMetrics live = service.live(instance).orElseThrow();

        assertThat(live.tieringReported()).isFalse();
        assertThat(live.memtableEntries()).isZero();
        assertThat(live.coldSstableCount()).isZero();
        assertThat(live.qps()).isEqualTo(1.0);
    }

    @Test
    void gatewayFailureYieldsEmptyInsteadOfZeros() {
        when(gateway.info(instance)).thenThrow(new RuntimeException("connection refused"));

        assertThat(service.live(instance)).isEmpty();
    }
}
