package io.tieringkv.webconsole.service;

import com.sun.net.httpserver.HttpServer;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.support.TestUsers;
import io.tieringkv.webconsole.web.dto.ClusterDtos.ClusterTopologyView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sBackupTriggerResult;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterListView;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sClusterSummary;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sScaleRequest;
import io.tieringkv.webconsole.web.dto.ClusterDtos.K8sUpgradeRequest;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v6.2.0 Stage C2：集群拓扑解析 + 纯 JDK HttpClient K8s Operator 桥接与 RBAC 测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ClusterTopologyAndK8sBridgeTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Test
    void parsesDistributedClusterInfoAndAllocates16384SlotsExactly() {
        InstanceService instanceService = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        DbInstance inst = new DbInstance();
        inst.setId(7L);
        inst.setName("dist-gw");
        inst.setHost("demo-gateway");
        inst.setPort(6379);
        when(instanceService.require(7L)).thenReturn(inst);

        String info = """
                # Server
                tieringkv_version:6.1.0
                # Stats
                total_keys:120
                # Keyspace
                db0:keys=120,expires=0,avg_ttl=0
                # Persistence
                rdb_bgsave_in_progress:0
                rdb_last_save_time:1764000000
                rdb_last_bgsave_status:ok
                rdb_last_bgsave_duration_ms:14
                # Cluster
                cluster_enabled:1
                cluster_mode:distributed
                cluster_node_id:gw-0
                cluster_regions:3
                cluster_slots_total:16384
                region_0:r1@demo-storage-0:7100
                region_1:r2@demo-storage-1:7100
                region_2:r3@demo-storage-2:7100
                # Transaction
                begin_txn:50
                active_txn:1
                committed_txn:48
                rollback_txn:1
                conflict_txn:2
                abort_txn:1
                recovery_txn:0
                lock_count:3
                txn_commit_latency_ms:2.345
                txn_prepare_latency_ms:0.812
                txn_network_retry:1
                txn_lock_wait_ms:0.120
                txn_region_count:3
                """;
        when(gateway.info(inst)).thenReturn(info);

        ClusterTopologyService service = new ClusterTopologyService(instanceService, gateway);
        ClusterTopologyView view = service.inspect(7L);

        assertThat(view.distributed()).isTrue();
        assertThat(view.mode()).isEqualTo("DISTRIBUTED");
        assertThat(view.nodeId()).isEqualTo("gw-0");
        assertThat(view.totalKeys()).isEqualTo(120L);
        assertThat(view.regions()).hasSize(3);
        int slotSum = view.regions().stream().mapToInt(r -> r.slotCount()).sum();
        assertThat(slotSum).isEqualTo(16384);
        assertThat(view.regions().get(0).slotCount()).isEqualTo(5462);
        assertThat(view.regions().get(1).slotCount()).isEqualTo(5461);
        assertThat(view.regions().get(2).regionId()).isEqualTo("r3");
        assertThat(view.transaction().committedTxn()).isEqualTo(48L);
        assertThat(view.transaction().commitLatencyMs()).isEqualTo(2.345);
        assertThat(view.persistence().lastStatus()).isEqualTo("ok");
        assertThat(view.persistence().lastSaveTime()).isEqualTo(1764000000L);
    }

    @Test
    void parsesStandaloneInfoGracefully() {
        InstanceService instanceService = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        DbInstance inst = new DbInstance();
        inst.setId(1L);
        inst.setName("local-6379");
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        when(instanceService.require(1L)).thenReturn(inst);
        when(gateway.info(inst)).thenReturn("# Server\r\nredis_version:6.1.0\r\n# Keyspace\r\ndb0:keys=5,expires=0,avg_ttl=0\r\n");

        ClusterTopologyService service = new ClusterTopologyService(instanceService, gateway);
        ClusterTopologyView view = service.inspect(1L);

        assertThat(view.distributed()).isFalse();
        assertThat(view.mode()).isEqualTo("STANDALONE");
        assertThat(view.regions()).hasSize(1);
        assertThat(view.regions().get(0).slotCount()).isEqualTo(16384);
        assertThat(view.transaction()).isNull();
    }

    @Test
    void k8sBridgeWorksOverRealHttpForListScaleUpgradeAndBackup() throws Exception {
        AtomicReference<String> lastMethod = new AtomicReference<>();
        AtomicReference<String> lastAuth = new AtomicReference<>();
        AtomicReference<String> lastContentType = new AtomicReference<>();
        AtomicReference<String> lastBody = new AtomicReference<>();

        HttpServer server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/apis/tieringkv.io/v1/namespaces/tiering-kv/tieringkvclusters", exchange -> {
            lastMethod.set(exchange.getRequestMethod());
            lastAuth.set(exchange.getRequestHeaders().getFirst("Authorization"));
            lastContentType.set(exchange.getRequestHeaders().getFirst("Content-Type"));
            String reqBody = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            lastBody.set(reqBody);

            String resp;
            if ("GET".equals(exchange.getRequestMethod())) {
                resp = """
                        {
                          "items": [{
                            "metadata": {"name": "demo", "namespace": "tiering-kv", "generation": 2, "creationTimestamp": "2026-11-26T00:00:00Z"},
                            "spec": {"metadataReplicas": 1, "storageReplicas": 3, "gatewayReplicas": 1, "regionIds": ["r1","r2","r3"], "image": "ghcr.io/holylaw7/tiering-kv:v6.1.0", "backupScheduleCron": "0 2 * * *"},
                            "status": {"phase": "READY", "readyMetadata": 1, "readyStorage": 3, "readyGateway": 1, "observedGeneration": 2, "lastBackupJob": "demo-backup-1", "lastBackupStatus": "SUCCEEDED"}
                          }]
                        }
                        """;
            } else {
                resp = """
                        {
                          "metadata": {"name": "demo", "namespace": "tiering-kv", "generation": 3},
                          "spec": {"metadataReplicas": 1, "storageReplicas": 4, "gatewayReplicas": 2, "regionIds": ["r1","r2","r3","r4"], "image": "ghcr.io/holylaw7/tiering-kv:v6.2.0"},
                          "status": {"phase": "SCALING", "readyMetadata": 1, "readyStorage": 3, "readyGateway": 1, "observedGeneration": 2}
                        }
                        """;
            }
            byte[] bytes = resp.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        try {
            ConsoleProperties props = new ConsoleProperties();
            props.getKubernetes().setEnabled(true);
            props.getKubernetes().setApiServer("http://127.0.0.1:" + server.getAddress().getPort());
            props.getKubernetes().setNamespace("tiering-kv");
            props.getKubernetes().setBearerToken("sa-test-token");

            AuditService audit = mock(AuditService.class);
            K8sOperatorBridgeService bridge = new K8sOperatorBridgeService(props, objectMapper, audit);

            K8sClusterListView list = bridge.listClusters();
            assertThat(list.enabled()).isTrue();
            assertThat(list.clusters()).hasSize(1);
            assertThat(list.clusters().get(0).name()).isEqualTo("demo");
            assertThat(list.clusters().get(0).phase()).isEqualTo("READY");
            assertThat(lastAuth.get()).isEqualTo("Bearer sa-test-token");

            K8sClusterSummary scaled = bridge.scaleCluster("demo", new K8sScaleRequest(4, 2, null));
            assertThat(scaled.storageReplicas()).isEqualTo(4);
            assertThat(lastMethod.get()).isEqualTo("PATCH");
            assertThat(lastContentType.get()).isEqualTo("application/merge-patch+json");
            assertThat(lastBody.get()).contains("\"storageReplicas\":4").contains("\"gatewayReplicas\":2");
            verify(audit).record(eq("K8S_CLUSTER_SCALE"), eq("tiering-kv/demo"), any(), eq(true));

            K8sClusterSummary upgraded = bridge.upgradeCluster("demo",
                    new K8sUpgradeRequest("ghcr.io/holylaw7/tiering-kv:v6.2.0"));
            assertThat(upgraded.image()).isEqualTo("ghcr.io/holylaw7/tiering-kv:v6.2.0");
            assertThat(lastBody.get()).contains("v6.2.0");

            K8sBackupTriggerResult backup = bridge.triggerBackup("demo");
            assertThat(backup.requestId()).startsWith("console-");
            assertThat(lastBody.get()).contains("tieringkv.io/backup-request-id");

            // 拒绝非法资源名（防路径注入）
            assertThatThrownBy(() -> bridge.triggerBackup("../bad"))
                    .isInstanceOf(BusinessException.class)
                    .hasMessageContaining("非法");
        } finally {
            server.stop(0);
        }
    }

    @Test
    void k8sEndpointsDegradeGracefullyWhenDisabledAndEnforceAdminRbac() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "viewer", "viewer123", Role.VIEWER);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer\",\"password\":\"viewer123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(body).path("data").path("token").asText();

        mockMvc.perform(get("/api/v1/cluster/k8s/clusters")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.enabled").value(false));

        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/demo/scale")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"storageReplicas\":3}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/demo/upgrade")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"image\":\"ghcr.io/holylaw7/tiering-kv:v6.2.0\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/cluster/k8s/clusters/demo/backup")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}
