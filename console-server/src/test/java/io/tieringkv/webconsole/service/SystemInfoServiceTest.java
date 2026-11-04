package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.SystemInfoView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TD-092：系统信息快照解析（对齐 Tiering-KV 自己的 INFO 字段）。 */
class SystemInfoServiceTest {

    private InstanceService instanceService;
    private RespGateway gateway;
    private SystemInfoService service;
    private DbInstance instance;

    private static final String INFO_TEXT = """
            # Keyspace
            db0:keys=42,expires=2,avg_ttl=0
            # Stats
            connections:10
            active_requests:1
            qps:12.5
            avg_latency_ms:0.85
            errors:3
            instantaneous_ops_per_sec:12.5
            total_keys:42
            # Memory
            used_memory:1048576
            maxmemory:134217728
            used_memory_jvm:228056600
            max_jvm_heap:8493465600
            # Clients
            connected_clients:3
            active_requests:1
            # Server
            redis_version:5.0.3
            tieringkv_version:5.1.0
            redis_mode:standalone
            os:Windows 11
            arch_bits:amd64
            process_id:30392
            tcp_port:6379
            uptime_in_seconds:3600
            jdk_version:21.0.12
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
        instance.setName("local");
        instance.setHost("127.0.0.1");
        instance.setPort(6379);
        when(instanceService.require(1L)).thenReturn(instance);
        when(gateway.execute(instance, "INFO")).thenReturn(bulk(INFO_TEXT));
        when(gateway.execute(instance, "CONFIG", "GET", "*"))
                .thenReturn(new RespReply.Array(List.of(
                        bulk("maxmemory"), bulk("134217728"),
                        bulk("appendfsync"), bulk("everysec"))));
    }

    @Test
    void parsesAllSections() {
        SystemInfoView view = service.snapshot(1L);

        assertThat(view.instance().name()).isEqualTo("local");
        assertThat(view.server().version()).isEqualTo("5.1.0");
        assertThat(view.server().mode()).isEqualTo("standalone");
        assertThat(view.server().uptimeSeconds()).isEqualTo(3600);
        assertThat(view.server().jdk()).isEqualTo("21.0.12");
        assertThat(view.clients().connected()).isEqualTo(3);
        assertThat(view.clients().totalConnections()).isEqualTo(10);
        assertThat(view.clients().errors()).isEqualTo(3);
        assertThat(view.memory().usedBytes()).isEqualTo(1048576);
        assertThat(view.memory().maxBytes()).isEqualTo(134217728);
        assertThat(view.memory().jvmUsedBytes()).isEqualTo(228056600);
        assertThat(view.stats().qps()).isEqualTo(12.5);
        assertThat(view.stats().avgLatencyMs()).isEqualTo(0.85);
        assertThat(view.stats().totalKeys()).isEqualTo(42);
        assertThat(view.keyspace()).singleElement()
                .satisfies(db -> {
                    assertThat(db.db()).isEqualTo("db0");
                    assertThat(db.keys()).isEqualTo(42);
                    assertThat(db.expires()).isEqualTo(2);
                });
        assertThat(view.config()).containsEntry("appendfsync", "everysec");
        assertThat(view.warnings()).isEmpty();
    }

    @Test
    void fallsBackToRedisVersionWhenTieringVersionMissing() throws IOException {
        when(gateway.execute(instance, "INFO")).thenReturn(bulk("""
                # Server
                redis_version:4.9.0
                """));

        assertThat(service.snapshot(1L).server().version()).isEqualTo("4.9.0");
    }

    @Test
    void configFailureBecomesWarningNotError() throws IOException {
        when(gateway.execute(instance, "CONFIG", "GET", "*"))
                .thenThrow(new IOException("CONFIG disabled"));

        SystemInfoView view = service.snapshot(1L);

        assertThat(view.config()).isEmpty();
        assertThat(view.warnings()).anySatisfy(w -> assertThat(w).contains("CONFIG"));
        assertThat(view.server().version()).isEqualTo("5.1.0");
    }

    @Test
    void infoFailureRaisesUpstream() throws IOException {
        when(gateway.execute(instance, "INFO")).thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> service.snapshot(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("读取 INFO 失败");
    }

    @Test
    void unknownInstanceRaisesNotFound() {
        when(instanceService.require(99L))
                .thenThrow(BusinessException.notFound("实例不存在"));

        assertThatThrownBy(() -> service.snapshot(99L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("实例不存在");
    }
}
