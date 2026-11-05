package io.tieringkv.observability;

import io.tieringkv.cache.block.CacheStatistics;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.storage.tiering.StorageMetrics;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Supplier;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** ADR-0355：INFO 标准 section 渲染。 */
class ServerInfoProviderTest {

    private static ServerInfoProvider provider(MemoryManager memory,
                                               AtomicLong keys) {
        MetricsRegistry metrics = new MetricsRegistry();
        metrics.connectionOpened();
        metrics.requestStarted();
        metrics.requestCompleted(2_000_000L);
        return new ServerInfoProvider(metrics, memory, keys::get, 6379, "9.9.9");
    }

    @Test
    void rendersAllRedisCompatibleSections() {
        MemoryManager memory = new MemoryManager(1024 * 1024);
        memory.add(2048);
        ServerInfoProvider provider = provider(memory, new AtomicLong(3));

        String info = provider.render();

        assertThat(info).contains("# Server\r\n")
                .contains("# Clients\r\n")
                .contains("# Memory\r\n")
                .contains("# Stats\r\n")
                .contains("# Keyspace\r\n")
                .contains("redis_version:9.9.9\r\n")
                .contains("tcp_port:6379\r\n")
                .contains("connected_clients:1\r\n")
                .contains("used_memory:2048\r\n")
                .contains("maxmemory:1048576\r\n")
                .contains("db0:keys=3,expires=0,avg_ttl=0\r\n");
    }

    @Test
    void keepsLegacyMetricKeysForBackwardCompatibility() {
        ServerInfoProvider provider = provider(
                new MemoryManager(1024), new AtomicLong(0));

        String info = provider.render();

        assertThat(info).contains("connections:1\r\n")
                .contains("active_requests:0\r\n")
                .contains("avg_latency_ms:2.000\r\n")
                .contains("errors:0\r\n");
    }

    @Test
    void emptyKeyspaceEmitsNoDbLine() {
        ServerInfoProvider provider = provider(
                new MemoryManager(1024), new AtomicLong(0));

        assertThat(provider.render()).doesNotContain("db0:keys=");
        assertThat(provider.sections().get("keyspace").get())
                .isEqualTo("# Keyspace\r\n");
    }

    @Test
    void sectionsAreAddressableIndividually() {
        Map<String, Supplier<String>> sections =
                provider(new MemoryManager(1024), new AtomicLong(1)).sections();

        assertThat(sections).containsKeys("server", "clients", "memory",
                "stats", "keyspace");
        assertThat(sections.get("memory").get())
                .startsWith("# Memory\r\n");
        assertThat(sections.get("stats").get())
                .contains("instantaneous_ops_per_sec:");
    }

    @Test
    void rejectsMissingCollaborators() {
        assertThatThrownBy(() -> new ServerInfoProvider(null,
                new MemoryManager(1024), () -> 0L, 6379, "v"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new ServerInfoProvider(new MetricsRegistry(),
                new MemoryManager(1024), null, 6379, "v"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void humanReadableBytesUseBinaryUnits() {
        assertThat(ServerInfoProvider.human(512)).isEqualTo("512.00B");
        assertThat(ServerInfoProvider.human(2048)).isEqualTo("2.00K");
        assertThat(ServerInfoProvider.human(3L * 1024 * 1024)).isEqualTo("3.00M");
    }

    @Test
    void exposesTieringSectionWithRealCounters() {
        MetricsRegistry metrics = new MetricsRegistry();
        MemoryManager memory = new MemoryManager(1024 * 1024);
        StorageMetrics storage = new StorageMetrics();
        storage.setMemoryGauges(4096, 1024 * 1024, 12);
        storage.setColdGauges(3, 8192);
        storage.recordFlush(2048, 1_000_000L);
        storage.migrationSubmitted();
        storage.migrationCompleted(true, 500_000L);
        CacheStatistics cache = new CacheStatistics();
        CacheStatistics.Snapshot snapshot = cache.snapshot();

        ServerInfoProvider provider = new ServerInfoProvider(
                metrics, memory, () -> 12L, 6379, "9.9.9",
                storage, () -> snapshot, null);
        String info = provider.render();

        assertThat(info).contains("# Tiering\r\n")
                .contains("tiering_memtable_used_bytes:4096\r\n")
                .contains("tiering_memtable_entries:12\r\n")
                .contains("tiering_cold_sstable_count:3\r\n")
                .contains("tiering_cold_disk_usage_bytes:8192\r\n")
                .contains("tiering_flush_count:1\r\n")
                .contains("tiering_migration_success:1\r\n")
                .contains("tiering_block_cache_hit_rate:0.0000\r\n");
    }

    @Test
    void omitsTieringSectionWhenMetricsUnavailable() {
        MetricsRegistry metrics = new MetricsRegistry();
        ServerInfoProvider provider = new ServerInfoProvider(
                metrics, new MemoryManager(1024), () -> 0L, 6379, "9.9.9");

        assertThat(provider.render()).doesNotContain("# Tiering");
    }
}