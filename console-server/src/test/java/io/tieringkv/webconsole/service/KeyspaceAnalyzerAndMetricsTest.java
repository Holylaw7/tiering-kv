package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.MetricPoint;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.observability.RequestCorrelationFilter;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v6.4.0 Stage D2：键空间大键/前缀/TTL 诊断分析器、Micrometer/Prometheus 业务指标、
 * W3C traceparent / X-Request-Id 全链路关联与 K8s 健康探针验证。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class KeyspaceAnalyzerAndMetricsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ConsoleMetricsBinder metricsBinder;

    @Autowired
    private AuditService auditService;

    @Autowired
    private OpLogRepository opLogRepository;

    @Test
    void analyzerComputesBigKeysPrefixesTtlAndRecommendations() throws Exception {
        InstanceService instanceService = mock(InstanceService.class);
        KeyspaceService keyspaceService = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);

        DbInstance inst = new DbInstance();
        inst.setId(9L);
        inst.setName("d2-analyzer");
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        when(instanceService.require(9L)).thenReturn(inst);
        when(gateway.execute(inst, "DBSIZE")).thenReturn(new RespReply.Integer(4L));
        when(gateway.execute(eq(inst), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), any()))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("cache:blob".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("cache:hot".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("user:profile".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("orphan".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspaceService.inspectBatch(eq(inst), anyList())).thenReturn(List.of(
                new KeyEntry("cache:blob", "string", -1L),
                new KeyEntry("cache:hot", "string", 1200L),
                new KeyEntry("user:profile", "hash", 7200L),
                new KeyEntry("orphan", "list", 90_000L)));
        when(gateway.executeBatch(eq(inst), anyList())).thenReturn(List.of(
                new RespReply.Integer(80_000L), // >= 64KB big string
                new RespReply.Integer(128L),
                new RespReply.Integer(1_500L),  // >= 1000 big collection
                new RespReply.Integer(12L)));

        KeyspaceAnalyzerService analyzer = new KeyspaceAnalyzerService(instanceService, keyspaceService, gateway);
        KeyspaceAnalyzerService.KeyspaceAnalysisView view = analyzer.analyze(9L,
                new KeyspaceAnalyzerService.AnalyzeRequest("*", 500, ":", 10));

        assertThat(view.scannedKeys()).isEqualTo(4L);
        assertThat(view.totalDbSize()).isEqualTo(4L);
        assertThat(view.truncated()).isFalse();
        assertThat(view.typeDistribution())
                .containsEntry("string", 2L)
                .containsEntry("hash", 1L)
                .containsEntry("list", 1L);
        assertThat(view.ttlDistribution().noExpire()).isEqualTo(1L);
        assertThat(view.ttlDistribution().under1h()).isEqualTo(1L);
        assertThat(view.ttlDistribution().between1hAnd24h()).isEqualTo(1L);
        assertThat(view.ttlDistribution().over24h()).isEqualTo(1L);
        assertThat(view.topPrefixes().get(0).prefix()).isEqualTo("cache:*");
        assertThat(view.topPrefixes().get(0).keyCount()).isEqualTo(2L);
        assertThat(view.bigKeys()).hasSize(4);
        assertThat(view.bigKeys().get(0).key()).isEqualTo("cache:blob");
        assertThat(view.bigKeys().get(0).sizeUnits()).isEqualTo(80_000L);
        assertThat(view.bigKeys().get(0).unitLabel()).isEqualTo("bytes");
        assertThat(view.bigKeys().get(1).key()).isEqualTo("user:profile");
        assertThat(view.bigKeys().get(1).unitLabel()).isEqualTo("fields");
        assertThat(view.recommendations())
                .anySatisfy(tip -> assertThat(tip).contains("64 KB"))
                .anySatisfy(tip -> assertThat(tip).contains("1,000"));
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void prometheusEndpointExposesCustomTieringKvMetrics() throws Exception {
        DbInstance inst = new DbInstance();
        inst.setId(77L);
        inst.setName("prom-inst");
        MetricPoint point = new MetricPoint();
        point.setUsedMemory(64L * 1024 * 1024);
        point.setMaxMemory(256L * 1024 * 1024);
        point.setOpsPerSec(12345.0d);
        point.setTotalKeys(5000L);
        metricsBinder.recordInstanceUp(inst, point, 4L, 0.42d);
        metricsBinder.recordBackup("SUCCESS");
        metricsBinder.recordRestore("SUCCEEDED");
        metricsBinder.recordAudit("KEY_SET", true);

        try {
            mockMvc.perform(get("/actuator/prometheus"))
                    .andExpect(status().isOk())
                    .andExpect(content().string(containsString("tieringkv_instance_up")))
                    .andExpect(content().string(containsString("tieringkv_instance_memory_ratio")))
                    .andExpect(content().string(containsString("tieringkv_instance_cold_tables")))
                    .andExpect(content().string(containsString("tieringkv_console_pool_active")))
                    .andExpect(content().string(containsString("tieringkv_console_pool_max")))
                    .andExpect(content().string(containsString("tieringkv_console_alerts_firing")))
                    .andExpect(content().string(containsString("tieringkv_console_backups_total")))
                    .andExpect(content().string(containsString("tieringkv_console_restores_total")))
                    .andExpect(content().string(containsString("tieringkv_console_audit_events_total")));
        } finally {
            metricsBinder.unregisterInstance(77L);
        }
    }

    @Test
    @WithMockUser(roles = "ADMIN")
    void requestCorrelationPropagatesHeaderTraceparentAndAuditSearch() throws Exception {
        String customReqId = "req-d2-trace-9001";
        mockMvc.perform(get("/actuator/health/liveness")
                        .header(RequestCorrelationFilter.HEADER_REQUEST_ID, customReqId))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestCorrelationFilter.HEADER_REQUEST_ID, customReqId))
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/actuator/health/readiness")
                        .header(RequestCorrelationFilter.HEADER_TRACEPARENT,
                                "00-4bf92f3577b34da6a3ce929d0e0e4736-00f067aa0ba902b7-01"))
                .andExpect(status().isOk())
                .andExpect(header().string(RequestCorrelationFilter.HEADER_REQUEST_ID,
                        "4bf92f3577b34da6a3ce929d0e0e4736"))
                .andExpect(jsonPath("$.status").value("UP"));

        // 验证 op_log.request_id 持久化与按 requestId 查询/CSV 导出
        OpLog saved = new OpLog();
        saved.setUsername("trace-admin");
        saved.setAction("KEY_SET");
        saved.setTarget("k:trace");
        saved.setDetail("ok");
        saved.setIp("127.0.0.1");
        saved.setSuccess(true);
        saved.setRequestId(customReqId);
        saved = opLogRepository.save(saved);
        try {
            mockMvc.perform(get("/api/v1/audit").param("requestId", customReqId))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.data.total").value(1))
                    .andExpect(jsonPath("$.data.items[0].requestId").value(customReqId));

            String csv = auditService.exportCsv(null, null, null, customReqId, null, null);
            assertThat(csv).contains("请求ID").contains(customReqId);
        } finally {
            opLogRepository.deleteById(saved.getId());
        }
    }

    @Test
    @WithMockUser(roles = "VIEWER")
    void viewerCanReachAnalyzeRouteValidationNotForbidden() throws Exception {
        mockMvc.perform(post("/api/v1/keys/analyze")
                        .param("instanceId", "999999")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"pattern\":\"*\",\"maxKeys\":500}"))
                .andExpect(status().isNotFound());
    }
}
