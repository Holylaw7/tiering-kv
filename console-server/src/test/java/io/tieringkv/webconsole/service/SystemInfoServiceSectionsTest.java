package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.SystemInfoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** M4：内核扩展 INFO 段落全景透出、空段过滤与敏感键脱敏校验。 */
class SystemInfoServiceSectionsTest {

    private InstanceService instanceService;
    private RespGateway gateway;
    private SystemInfoService service;
    private DbInstance instance;

    private static final String EXTENDED_INFO_TEXT = """
            # Server
            redis_version:5.2.0
            tieringkv_version:5.2.0
            redis_mode:cluster
            tcp_port:6379
            # EmptySection

            # Tiering
            tiering_memtable_used_bytes:2048
            tiering_hot_keys:128
            tiering_hot_key_share:0.80
            tiering_cold_sstable_count:4
            # Persistence
            rdb_bgsave_in_progress:0
            rdb_last_bgsave_status:ok
            # Cluster
            cluster_enabled:1
            cluster_state:ok
            cluster_token:super-secret-cluster-token
            # Replication
            role:master
            masterauth:top-secret-master-auth
            replication_replicas:2
            # Txn
            txn_active:3
            txn_committed_total:99
            # MVCC
            mvcc_versions_total:420
            # Vector
            vector_count:1024
            # MultiModel
            multimodel_ts_writes:55
            # Backup
            backup_total:7
            s3_access_key:AKIAEXAMPLEKEY
            s3_secret_key:wJalrXUtnFEMI/K7MDENG/bPxRfiCYEXAMPLEKEY
            # Tracing
            tracing_enabled:1
            tracing_api_key:trace-secret-key
            # Stats
            total_keys:256
            evicted_keys:1
            expired_keys:5
            keyspace_hits:999
            # Keyspace
            db0:keys=256,expires=5,avg_ttl=1000
            """;

    private static RespReply bulk(String text) {
        return new RespReply.Bulk(text.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() throws IOException {
        instanceService = mock(InstanceService.class);
        gateway = mock(RespGateway.class);
        service = new SystemInfoService(instanceService, gateway);
        instance = new DbInstance();
        instance.setId(1L);
        instance.setName("cluster-node-1");
        instance.setHost("127.0.0.1");
        instance.setPort(6379);
        when(instanceService.require(1L)).thenReturn(instance);
        when(gateway.execute(instance, "INFO")).thenReturn(bulk(EXTENDED_INFO_TEXT));
        when(gateway.execute(instance, "CONFIG", "GET", "*"))
                .thenReturn(new RespReply.Array(List.of(
                        bulk("maxmemory"), bulk("134217728"),
                        bulk("requirepass"), bulk("my-prod-pass"),
                        bulk("masterauth"), bulk(""),
                        bulk("tls_private_key"), bulk("PEM-DATA"),
                        bulk("adminPassword"), bulk("pw123"))));
    }

    @Test
    void exposesAllNonEmptyExtendedSectionsInOrderAndSkipsEmptySections() {
        SystemInfoView view = service.snapshot(1L);

        assertThat(view.sections().keySet()).containsExactly(
                "Server",
                "Tiering",
                "Persistence",
                "Cluster",
                "Replication",
                "Txn",
                "MVCC",
                "Vector",
                "MultiModel",
                "Backup",
                "Tracing",
                "Stats",
                "Keyspace"
        );
        assertThat(view.sections()).doesNotContainKeys("default", "EmptySection");
        assertThat(view.sections().get("Tiering"))
                .containsEntry("tiering_memtable_used_bytes", "2048")
                .containsEntry("tiering_hot_keys", "128")
                .containsEntry("tiering_hot_key_share", "0.80");
        assertThat(view.sections().get("Vector")).containsEntry("vector_count", "1024");
        assertThat(view.sections().get("MVCC")).containsEntry("mvcc_versions_total", "420");
    }

    @Test
    void masksSensitiveKeysWithoutFalsePositivesOnMetricKeyNames() {
        SystemInfoView view = service.snapshot(1L);

        // INFO 段落敏感键全部脱敏
        assertThat(view.sections().get("Cluster"))
                .containsEntry("cluster_state", "ok")
                .containsEntry("cluster_token", SystemInfoService.MASKED_VALUE);
        assertThat(view.sections().get("Replication"))
                .containsEntry("masterauth", SystemInfoService.MASKED_VALUE)
                .containsEntry("replication_replicas", "2");
        assertThat(view.sections().get("Backup"))
                .containsEntry("backup_total", "7")
                .containsEntry("s3_access_key", SystemInfoService.MASKED_VALUE)
                .containsEntry("s3_secret_key", SystemInfoService.MASKED_VALUE);
        assertThat(view.sections().get("Tracing"))
                .containsEntry("tracing_enabled", "1")
                .containsEntry("tracing_api_key", SystemInfoService.MASKED_VALUE);

        // 合法的键计数/键空间指标不得被误伤
        assertThat(view.sections().get("Stats"))
                .containsEntry("total_keys", "256")
                .containsEntry("evicted_keys", "1")
                .containsEntry("expired_keys", "5")
                .containsEntry("keyspace_hits", "999");

        // CONFIG 敏感键脱敏且空口令保留空串
        assertThat(view.config())
                .containsEntry("maxmemory", "134217728")
                .containsEntry("requirepass", SystemInfoService.MASKED_VALUE)
                .containsEntry("masterauth", "")
                .containsEntry("tls_private_key", SystemInfoService.MASKED_VALUE)
                .containsEntry("adminPassword", SystemInfoService.MASKED_VALUE);
    }

    @Test
    void remainsCompatibleWithStandardRedisAndEmptyInfo() throws IOException {
        when(gateway.execute(instance, "INFO")).thenReturn(bulk("""
                # Server
                redis_version:7.2.4
                # CPU
                used_cpu_sys:1.25
                """));

        SystemInfoView redisView = service.snapshot(1L);
        assertThat(redisView.sections().keySet()).containsExactly("Server", "CPU");
        assertThat(redisView.sections().get("CPU")).containsEntry("used_cpu_sys", "1.25");

        when(gateway.execute(instance, "INFO")).thenReturn(new RespReply.Bulk(null));
        SystemInfoView emptyView = service.snapshot(1L);
        assertThat(emptyView.sections()).isEmpty();
        assertThat(emptyView.warnings()).contains("INFO 返回为空");
    }
}
