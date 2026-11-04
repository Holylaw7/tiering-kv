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

/** TD-092：系统信息快照解析（INFO + CONFIG GET）。 */
class SystemInfoServiceTest {

    private InstanceService instanceService;
    private RespGateway gateway;
    private SystemInfoService service;
    private DbInstance instance;

    private static final String INFO_TEXT = """
            # Server
            redis_version:5.1.0
            uptime_in_seconds:3600
            tcp_port:6379
            os:Linux
            arch_bits:64
            process_id:1234
            # Clients
            connected_clients:3
            blocked_clients:0
            # Memory
            used_memory:1048576
            used_memory_peak:2097152
            maxmemory:134217728
            # Stats
            total_connections_received:10
            total_commands_processed:500
            keyspace_hits:80
            keyspace_misses:20
            # Keyspace
            db0:keys=42,expires=2,avg_ttl=0
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
        assertThat(view.server().uptimeSeconds()).isEqualTo(3600);
        assertThat(view.clients().connected()).isEqualTo(3);
        assertThat(view.memory().usedBytes()).isEqualTo(1048576);
        assertThat(view.memory().maxBytes()).isEqualTo(134217728);
        assertThat(view.stats().totalCommands()).isEqualTo(500);
        assertThat(view.stats().hitRate()).isEqualTo(0.8);
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
