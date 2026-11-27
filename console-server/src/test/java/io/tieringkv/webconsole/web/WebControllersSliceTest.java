package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.PageResult;
import io.tieringkv.webconsole.domain.AlertEvent;
import io.tieringkv.webconsole.domain.AlertRule;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupVerifyStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.TerminalHistory;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.service.AlertChannelService;
import io.tieringkv.webconsole.service.AlertService;
import io.tieringkv.webconsole.service.BackupCapacityService;
import io.tieringkv.webconsole.service.BackupRetentionService;
import io.tieringkv.webconsole.service.BackupService;
import io.tieringkv.webconsole.service.ClusterTopologyService;
import io.tieringkv.webconsole.service.InstanceService;
import io.tieringkv.webconsole.service.K8sOperatorBridgeService;
import io.tieringkv.webconsole.service.KeyspaceAnalyzerService;
import io.tieringkv.webconsole.service.KeyspaceService;
import io.tieringkv.webconsole.service.MetricService;
import io.tieringkv.webconsole.service.MonitorService;
import io.tieringkv.webconsole.service.MultiModelService;
import io.tieringkv.webconsole.service.SystemInfoService;
import io.tieringkv.webconsole.service.TerminalService;
import io.tieringkv.webconsole.service.TieringTableService;
import io.tieringkv.webconsole.support.TestUsers;
import io.tieringkv.webconsole.web.dto.AlertChannelView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.ClusterTopologyView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sBackupTriggerResult;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterListView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterSummary;
import io.tieringkv.webconsole.web.dto.InstanceView;
import io.tieringkv.webconsole.web.dto.KeyDetail;
import io.tieringkv.webconsole.web.dto.KeyScanPage;
import io.tieringkv.webconsole.web.dto.LiveMetrics;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonQueryView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesPoint;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesRangeView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorCollectionsView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchResultView;
import io.tieringkv.webconsole.web.dto.TerminalResult;
import io.tieringkv.webconsole.web.dto.TrendPoint;
import io.tieringkv.webconsole.web.dto.WebhookTestResult;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyBoolean;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Wave 2B（T1）：控制面 REST 控制器切片回归测试。
 *
 * <p>集中验证 Dashboard / MultiModel / Keyspace / Backup / Alert / AlertChannel /
 * Instance / Cluster / Terminal / System 控制器的路由映射、参数校验（400）、
 * RBAC 角色隔离（VIEWER / OPS / ADMIN）与全分支返回契约。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class WebControllersSliceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @MockitoBean
    private MultiModelService multiModelService;
    @MockitoBean
    private KeyspaceService keyspaceService;
    @MockitoBean
    private KeyspaceAnalyzerService analyzerService;
    @MockitoBean
    private InstanceService instanceService;
    @MockitoBean
    private MonitorService monitorService;
    @MockitoBean
    private MetricService metricService;
    @MockitoBean
    private AlertService alertService;
    @MockitoBean
    private AlertChannelService alertChannelService;
    @MockitoBean
    private ClusterTopologyService topologyService;
    @MockitoBean
    private K8sOperatorBridgeService k8sBridgeService;
    @MockitoBean
    private TerminalService terminalService;
    @MockitoBean
    private BackupService backupService;
    @MockitoBean
    private BackupRetentionService backupRetentionService;
    @MockitoBean
    private BackupCapacityService backupCapacityService;
    @MockitoBean
    private SystemInfoService systemInfoService;
    @MockitoBean
    private TieringTableService tieringTableService;

    private String viewerToken;
    private String opsToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "w2b_viewer", "viewer123", Role.VIEWER);
        TestUsers.ensure(userRepository, passwordEncoder, "w2b_ops", "ops123456", Role.OPS);
        TestUsers.ensure(userRepository, passwordEncoder, "w2b_admin", "admin1234", Role.ADMIN);
        viewerToken = login("w2b_viewer", "viewer123");
        opsToken = login("w2b_ops", "ops123456");
        adminToken = login("w2b_admin", "admin1234");
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    void dashboardControllerCoversEmptyDefaultExplicitLiveNullAndTrendsValidation() throws Exception {
        when(alertService.firingCount()).thenReturn(2L);
        when(alertService.resolvedTodayCount()).thenReturn(5L);

        // 1. 空实例列表分支
        when(instanceService.list()).thenReturn(List.of());
        mockMvc.perform(get("/api/v1/dashboard/overview").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.firingAlerts").value(2))
                .andExpect(jsonPath("$.data.totalKeys").value(0));

        // 2. 未指定 instanceId -> 默认取首个实例，且 live == Optional.empty()
        DbInstance inst1 = new DbInstance();
        inst1.setId(1L);
        inst1.setName("node-1");
        inst1.setHost("127.0.0.1");
        inst1.setPort(6379);
        DbInstance inst2 = new DbInstance();
        inst2.setId(2L);
        inst2.setName("node-2");
        inst2.setHost("127.0.0.1");
        inst2.setPort(6380);
        when(instanceService.list()).thenReturn(List.of(InstanceView.of(inst1), InstanceView.of(inst2)));
        when(instanceService.require(1L)).thenReturn(inst1);
        when(monitorService.live(inst1)).thenReturn(Optional.empty());
        mockMvc.perform(get("/api/v1/dashboard/overview").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalKeys").value(0))
                .andExpect(jsonPath("$.data.uptimeSeconds").value(0));

        // 3. 显式指定 instanceId=2 且 live 非空
        LiveMetrics live = new LiveMetrics(
                "6.4.0", 3600L, 1024L, 4096L, 2048L, 1234L, 5, 1,
                100.0, 99.0, 0.5, 0L, 8192L,
                0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0,
                0, 0, 0, 0, 0, false, 0, 0, 0, 0, 0, 0);
        when(instanceService.require(2L)).thenReturn(inst2);
        when(monitorService.live(inst2)).thenReturn(Optional.of(live));
        mockMvc.perform(get("/api/v1/dashboard/overview")
                        .param("instanceId", "2")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.totalKeys").value(1234))
                .andExpect(jsonPath("$.data.uptimeSeconds").value(3600));

        // 4. trends 非法 minutes<=0 -> 400；正数 -> 200
        mockMvc.perform(get("/api/v1/dashboard/trends")
                        .param("instanceId", "1")
                        .param("minutes", "0")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isBadRequest());

        when(metricService.trends(1L, 30)).thenReturn(List.of(
                new TrendPoint("2026-11-27T10:00:00", 50.0, 1.0, 3, 100L, 20L, 0L, 1000L)));
        mockMvc.perform(get("/api/v1/dashboard/trends")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.length()").value(1));
    }

    @Test
    void multiModelControllerCoversAllVectorTimeSeriesAndJsonRoutesWithRbacAndValidation() throws Exception {
        // Vector
        when(multiModelService.listVectorCollections(1L))
                .thenReturn(new VectorCollectionsView(true, null, List.of(), 0L));
        mockMvc.perform(get("/api/v1/multimodel/vector/collections")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // VIEWER 写向量被拒 403；OPS 合法写入 200；非法请求体 400
        mockMvc.perform(post("/api/v1/multimodel/vector/add")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"collection\":\"c\",\"id\":\"v1\",\"values\":[0.1,0.2]}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/multimodel/vector/add")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"id\":\"\",\"values\":[]}"))
                .andExpect(status().isBadRequest());
        mockMvc.perform(post("/api/v1/multimodel/vector/add")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"collection\":\"c\",\"id\":\"v1\",\"values\":[0.1,0.2]}"))
                .andExpect(status().isOk());

        when(multiModelService.searchVector(any()))
                .thenReturn(new VectorSearchResultView(true, null, "c", 5, 1L, List.of()));
        mockMvc.perform(post("/api/v1/multimodel/vector/search")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"collection\":\"c\",\"vector\":[0.1,0.2],\"topK\":5}"))
                .andExpect(status().isOk());

        when(multiModelService.deleteVector(1L, "c", "v1")).thenReturn(true);
        mockMvc.perform(delete("/api/v1/multimodel/vector/item")
                        .param("instanceId", "1").param("collection", "c").param("id", "v1")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));

        when(multiModelService.dropVectorCollection(1L, "c")).thenReturn(true);
        mockMvc.perform(delete("/api/v1/multimodel/vector/collection")
                        .param("instanceId", "1").param("collection", "c")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value(true));

        when(multiModelService.checkpointVector(1L, "c")).thenReturn("OK");
        mockMvc.perform(post("/api/v1/multimodel/vector/checkpoint")
                        .param("instanceId", "1").param("collection", "c")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data").value("OK"));

        // TimeSeries
        when(multiModelService.listTimeSeries(1L)).thenReturn(new TimeSeriesKeysView(true, null, List.of()));
        mockMvc.perform(get("/api/v1/multimodel/timeseries/keys")
                        .param("instanceId", "1").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(multiModelService.addTimeSeriesPoint(any())).thenReturn(new TimeSeriesPoint(1000L, 2.5));
        mockMvc.perform(post("/api/v1/multimodel/timeseries/add")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"key\":\"ts:cpu\",\"timestamp\":1000,\"value\":2.5}"))
                .andExpect(status().isOk());

        when(multiModelService.incrTimeSeries(any())).thenReturn(1001L);
        mockMvc.perform(post("/api/v1/multimodel/timeseries/incrby")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"key\":\"ts:cpu\",\"delta\":1.0}"))
                .andExpect(status().isOk());

        when(multiModelService.queryTimeSeriesRange(eq(1L), eq("ts:cpu"), any(), any(), any(), any(), any()))
                .thenReturn(new TimeSeriesRangeView(true, null, "ts:cpu", 0L, null, null, List.of(), null));
        mockMvc.perform(get("/api/v1/multimodel/timeseries/range")
                        .param("instanceId", "1").param("key", "ts:cpu")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        // JSON Path
        when(multiModelService.listJsonKeys(1L, "*", 50)).thenReturn(new JsonKeysView(true, null, List.of("doc:1")));
        mockMvc.perform(get("/api/v1/multimodel/json/keys")
                        .param("instanceId", "1").param("pattern", "*").param("limit", "50")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(multiModelService.getJson(1L, "doc:1", "$"))
                .thenReturn(new JsonQueryView(true, null, true, "doc:1", "$", "OBJECT", "{}", "{}", List.of()));
        mockMvc.perform(get("/api/v1/multimodel/json/get")
                        .param("instanceId", "1").param("key", "doc:1").param("path", "$")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/multimodel/json/set")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"key\":\"doc:1\",\"path\":\"$\",\"value\":\"{\\\"a\\\":1}\"}"))
                .andExpect(status().isOk());

        when(multiModelService.deleteJson(1L, "doc:1", "$.a")).thenReturn(1L);
        mockMvc.perform(delete("/api/v1/multimodel/json/del")
                        .param("instanceId", "1").param("key", "doc:1").param("path", "$.a")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        when(multiModelService.numIncrJson(any())).thenReturn("[2]");
        mockMvc.perform(post("/api/v1/multimodel/json/numincrby")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"key\":\"doc:1\",\"path\":\"$.a\",\"delta\":1}"))
                .andExpect(status().isOk());

        when(multiModelService.arrAppendJson(any())).thenReturn(3L);
        mockMvc.perform(post("/api/v1/multimodel/json/arrappend")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"key\":\"doc:1\",\"path\":\"$.arr\",\"value\":\"1\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void keyspaceControllerCoversAnalyzeScanDetailMutationsAndRbac() throws Exception {
        when(analyzerService.analyze(eq(1L), any())).thenReturn(null);
        mockMvc.perform(post("/api/v1/keys/analyze")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(keyspaceService.scan(1L, "0", "*", 50)).thenReturn(new KeyScanPage("0", List.of(), true));
        mockMvc.perform(get("/api/v1/keys/scan")
                        .param("instanceId", "1").param("cursor", "0").param("pattern", "*")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(keyspaceService.detail(1L, "k1")).thenReturn(new KeyDetail("k1", "string", -1L, "v1"));
        mockMvc.perform(get("/api/v1/keys/detail")
                        .param("instanceId", "1").param("key", "k1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk()).andExpect(jsonPath("$.data.value").value("v1"));

        mockMvc.perform(put("/api/v1/keys")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k1\",\"value\":\"v2\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(put("/api/v1/keys")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k1\",\"value\":\"v2\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/keys/hash-field")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"h1\",\"field\":\"f1\",\"value\":\"v1\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/keys/hash-field")
                        .param("instanceId", "1").param("key", "h1").param("field", "f1")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        mockMvc.perform(put("/api/v1/keys/ttl")
                        .param("instanceId", "1")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"key\":\"k1\",\"ttlSeconds\":60}"))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/keys")
                        .param("instanceId", "1").param("key", "k1")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());
        verify(keyspaceService).delete(1L, "k1");
    }

    @Test
    void backupControllerCoversAllEndpointsRestoreBranchesAndRbac() throws Exception {
        BackupRecord rec = new BackupRecord();
        rec.setId(10L);
        when(backupService.list(1L)).thenReturn(List.of(rec));
        mockMvc.perform(get("/api/v1/backups").param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(backupService.export(1L)).thenReturn(rec);
        mockMvc.perform(post("/api/v1/backups").param("instanceId", "1")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        // restore 三分支：无体 -> false；体 false -> false；体 true -> true；OPS -> 403
        when(backupService.restore(eq(10L), anyBoolean())).thenReturn(rec);
        mockMvc.perform(post("/api/v1/backups/10/restore")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/backups/10/restore")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/backups/10/restore")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"createSafetySnapshot\":false}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/backups/10/restore")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"createSafetySnapshot\":true}"))
                .andExpect(status().isOk());
        verify(backupService).restore(10L, true);

        when(backupService.verify(10L)).thenReturn(new BackupService.BackupVerifyResult(
                10L, "b.jsonl", BackupVerifyStatus.VERIFIED, "a", "a", 1L, 10L, LocalDateTime.now(), "ok"));
        mockMvc.perform(post("/api/v1/backups/10/verify")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        when(backupService.previewRestore(10L)).thenReturn(new BackupService.RestorePreviewView(
                10L, "b.jsonl", BackupVerifyStatus.VERIFIED, true, 1, 0, 1, 0, Map.of(), List.of(), null));
        mockMvc.perform(post("/api/v1/backups/10/restore/preview")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        when(backupService.openDownload(10L)).thenReturn(new BackupService.DownloadStream(
                "b.jsonl", new ByteArrayInputStream("ok".getBytes(StandardCharsets.UTF_8))));
        mockMvc.perform(get("/api/v1/backups/10/download")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", "attachment; filename=\"b.jsonl\""));

        when(backupRetentionService.purge()).thenReturn(new BackupRetentionService.PurgeReport(
                LocalDateTime.now(), LocalDateTime.now(), 1, 1, 1, 0, 0, 0));
        mockMvc.perform(post("/api/v1/backups/purge")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        when(backupCapacityService.view()).thenReturn(null);
        mockMvc.perform(get("/api/v1/backups/capacity").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        when(backupRetentionService.view()).thenReturn(null);
        mockMvc.perform(get("/api/v1/backups/retention").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        mockMvc.perform(delete("/api/v1/backups/10").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        when(backupService.triggerCheckpoint(1L)).thenReturn(new BackupService.CheckpointResult(true, "ok", 100L));
        mockMvc.perform(post("/api/v1/backups/checkpoint").param("instanceId", "1")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        when(backupService.checkpointStatus(1L)).thenReturn(
                new BackupService.CheckpointStatusView(true, false, 100L, "ok", 1L, 1000L, null));
        mockMvc.perform(get("/api/v1/backups/checkpoint/status").param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }

    @Test
    void alertChannelInstanceClusterTerminalAndSystemControllersCoverRoutesAndRbac() throws Exception {
        // AlertController
        AlertRule rule = new AlertRule();
        rule.setId(1L);
        when(alertService.rules()).thenReturn(List.of(rule));
        when(alertService.create(any())).thenReturn(rule);
        when(alertService.update(eq(1L), any())).thenReturn(rule);
        when(alertService.events(any(), anyInt(), anyInt())).thenReturn(PageResult.of(List.<AlertEvent>of(), 0, 1, 20));

        mockMvc.perform(get("/api/v1/alerts/rules").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        String ruleJson = "{\"name\":\"high-qps\",\"metric\":\"qps\",\"operator\":\"GT\",\"threshold\":1000,\"durationSeconds\":10,\"level\":\"WARN\"}";
        mockMvc.perform(post("/api/v1/alerts/rules")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/alerts/rules")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/alerts/rules/1")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(ruleJson))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/alerts/rules/1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/alerts/events").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/alerts/events/1/resolve").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        // AlertChannelController
        AlertChannelView chView = new AlertChannelView(1L, "h", "WEBHOOK", "https://a.example.com", false, 300, true, null, null);
        when(alertChannelService.list()).thenReturn(List.of(chView));
        when(alertChannelService.create(any())).thenReturn(chView);
        when(alertChannelService.update(eq(1L), any())).thenReturn(chView);
        when(alertChannelService.test(1L)).thenReturn(new WebhookTestResult(true, 200, "OK", 10L));

        mockMvc.perform(get("/api/v1/alerts/channels").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        String chJson = "{\"name\":\"h\",\"type\":\"WEBHOOK\",\"url\":\"https://a.example.com\",\"silenceSeconds\":300}";
        mockMvc.perform(post("/api/v1/alerts/channels").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(chJson))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/alerts/channels/1").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(chJson))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/alerts/channels/1/test").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/alerts/channels/1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        // InstanceController
        DbInstance inst = new DbInstance();
        inst.setId(1L);
        inst.setName("n1");
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        InstanceView iv = InstanceView.of(inst);
        when(instanceService.list()).thenReturn(List.of(iv));
        when(instanceService.create(any())).thenReturn(iv);
        when(instanceService.update(eq(1L), any())).thenReturn(iv);
        when(instanceService.probe(1L)).thenReturn(iv);

        String instJson = "{\"name\":\"n1\",\"host\":\"127.0.0.1\",\"port\":6379}";
        mockMvc.perform(get("/api/v1/instances").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/instances").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(instJson))
                .andExpect(status().isOk());
        mockMvc.perform(put("/api/v1/instances/1").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content(instJson))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/instances/1/probe").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(delete("/api/v1/instances/1").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/instances/1").header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        // ClusterController
        when(topologyService.inspect(1L)).thenReturn(
                new ClusterTopologyView(1L, "n1", "standalone", false, "id", "6.4", 0, 0, 0, List.of(), null, null));
        when(k8sBridgeService.listClusters()).thenReturn(new K8sClusterListView(false, "ns", "http://k8s", "off", List.of()));
        K8sClusterSummary summary = new K8sClusterSummary("c1", "ns", "READY", "img", 1, 1, 2, 2, 1, 1,
                List.of(), 1L, 1L, null, null, null, null, null, null, null);
        when(k8sBridgeService.scaleCluster(eq("c1"), any())).thenReturn(summary);
        when(k8sBridgeService.upgradeCluster(eq("c1"), any())).thenReturn(summary);
        when(k8sBridgeService.triggerBackup("c1")).thenReturn(new K8sBackupTriggerResult("c1", "ns", "req-1", "ok"));

        mockMvc.perform(get("/api/v1/cluster/topology").param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/cluster/k8s/clusters").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/c1/scale").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storageReplicas\":3}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/c1/scale").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"storageReplicas\":3}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/c1/upgrade").header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"image\":\"tk:6.5.0\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/c1/backup").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isOk());

        // TerminalController & SystemController
        when(terminalService.execute(1L, "PING")).thenReturn(new TerminalResult("simple", "PONG", 1L, false));
        when(terminalService.history(1, 20)).thenReturn(PageResult.of(List.<TerminalHistory>of(), 0, 1, 20));
        mockMvc.perform(post("/api/v1/terminal/execute").header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"instanceId\":1,\"command\":\"PING\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/terminal/history").header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());

        when(tieringTableService.list(1L)).thenReturn(null);
        when(systemInfoService.snapshot(1L)).thenReturn(null);
        mockMvc.perform(get("/api/v1/system/tables").param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/system/info").param("instanceId", "1")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk());
    }
}
