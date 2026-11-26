package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.support.TestUsers;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonArrAppendRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonNumIncrRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonQueryView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.JsonSetRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesIncrByRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesKeysView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.TimeSeriesRangeView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorAddRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorCollectionsView;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchRequest;
import io.tieringkv.webconsole.web.dto.MultiModelDtos.VectorSearchResultView;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.ObjectMapper;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** v6.2.0 Stage C1：多模型工作台服务单测与控制器 RBAC 校验。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class MultiModelServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private InstanceService mockInstanceService;
    private RespGateway mockGateway;
    private AuditService mockAuditService;
    private MultiModelService service;
    private DbInstance instance;

    private static RespReply.Bulk bulk(String text) {
        return new RespReply.Bulk(text.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        mockInstanceService = mock(InstanceService.class);
        mockGateway = mock(RespGateway.class);
        mockAuditService = mock(AuditService.class);
        service = new MultiModelService(mockInstanceService, mockGateway, mockAuditService, objectMapper);

        instance = new DbInstance();
        instance.setId(1L);
        instance.setName("mm-test");
        instance.setHost("127.0.0.1");
        instance.setPort(6379);
        when(mockInstanceService.require(1L)).thenReturn(instance);
    }

    @Test
    void vectorOperationsWorkAndDegradeGracefullyWhenUnsupported() throws Exception {
        when(mockGateway.execute(instance, "VECTOR.LIST")).thenReturn(new RespReply.Array(List.of(
                new RespReply.Array(List.of(bulk("default"), new RespReply.Integer(3))),
                new RespReply.Array(List.of(bulk("docs"), new RespReply.Integer(12))))));

        VectorCollectionsView collections = service.listVectorCollections(1L);
        assertThat(collections.supported()).isTrue();
        assertThat(collections.totalVectors()).isEqualTo(15L);
        assertThat(collections.collections()).hasSize(2);

        when(mockGateway.execute(instance, "VECTOR.ADD", "COLLECTION", "docs", "doc-1", "3", "0.1", "0.2", "0.3"))
                .thenReturn(new RespReply.Simple("OK"));
        service.addVector(new VectorAddRequest(1L, "docs", "doc-1", List.of(0.1, 0.2, 0.3)));
        verify(mockAuditService).record(eq("MULTIMODEL_VECTOR_ADD"), any(), eq("dim=3"), eq(true));

        when(mockGateway.execute(instance, "VECTOR.SEARCH", "COLLECTION", "docs", "3", "0.1", "0.2", "0.3", "TOPK", "5"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Array(List.of(bulk("doc-1"), bulk("0.998"))))));
        VectorSearchResultView search = service.searchVector(
                new VectorSearchRequest(1L, "docs", List.of(0.1, 0.2, 0.3), 5));
        assertThat(search.supported()).isTrue();
        assertThat(search.hits()).hasSize(1);
        assertThat(search.hits().get(0).id()).isEqualTo("doc-1");
        assertThat(search.hits().get(0).score()).isEqualTo(0.998);

        when(mockGateway.execute(instance, "VECTOR.DEL", "COLLECTION", "docs", "doc-1"))
                .thenReturn(new RespReply.Integer(1));
        assertThat(service.deleteVector(1L, "docs", "doc-1")).isTrue();

        when(mockGateway.execute(instance, "VECTOR.CHECKPOINT", "COLLECTION", "docs"))
                .thenReturn(new RespReply.Simple("OK"));
        assertThat(service.checkpointVector(1L, "docs")).isEqualTo("OK");

        when(mockGateway.execute(instance, "VECTOR.DROP", "docs")).thenReturn(new RespReply.Integer(1));
        assertThat(service.dropVectorCollection(1L, "docs")).isTrue();

        // 普通 Redis 优雅降级
        when(mockGateway.execute(instance, "VECTOR.LIST"))
                .thenReturn(new RespReply.Error("ERR unknown command 'VECTOR.LIST'"));
        VectorCollectionsView unsupported = service.listVectorCollections(1L);
        assertThat(unsupported.supported()).isFalse();
        assertThat(unsupported.collections()).isEmpty();
    }

    @Test
    void timeSeriesOperationsParsePointsAggregationsAndDegradeGracefully() throws Exception {
        when(mockGateway.execute(instance, "TS.MRANGE", "-inf", "+inf")).thenReturn(new RespReply.Array(List.of(
                new RespReply.Array(List.of(bulk("ts:cpu"), new RespReply.Array(List.of(
                        new RespReply.Array(List.of(new RespReply.Integer(1000L), bulk("42.5"))),
                        new RespReply.Array(List.of(new RespReply.Integer(2000L), bulk("68.0"))))))))));

        TimeSeriesKeysView keys = service.listTimeSeries(1L);
        assertThat(keys.supported()).isTrue();
        assertThat(keys.series()).hasSize(1);
        assertThat(keys.series().get(0).key()).isEqualTo("ts:cpu");
        assertThat(keys.series().get(0).length()).isEqualTo(2L);
        assertThat(keys.series().get(0).latestValue()).isEqualTo(68.0);

        when(mockGateway.execute(instance, "TS.ADD", "ts:cpu", "3000", "55.5"))
                .thenReturn(new RespReply.Simple("OK"));
        assertThat(service.addTimeSeriesPoint(new TimeSeriesAddRequest(1L, "ts:cpu", 3000L, 55.5)).value())
                .isEqualTo(55.5);

        when(mockGateway.execute(instance, "TS.INCRBY", "ts:cpu", "2.5", "TIMESTAMP", "3000"))
                .thenReturn(new RespReply.Integer(3000L));
        assertThat(service.incrTimeSeries(new TimeSeriesIncrByRequest(1L, "ts:cpu", 2.5, 3000L))).isEqualTo(3000L);

        when(mockGateway.execute(instance, "TS.RANGE", "ts:cpu", "-inf", "+inf", "AGGREGATION", "AVG", "60000", "COUNT", "100"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Array(List.of(new RespReply.Integer(0L), bulk("55.25"))))));
        when(mockGateway.execute(instance, "TS.LEN", "ts:cpu")).thenReturn(new RespReply.Integer(3));
        when(mockGateway.execute(instance, "TS.REDUCE", "ts:cpu", "AGGREGATION", "AVG"))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Integer(1000L), bulk("55.25"))));

        TimeSeriesRangeView range = service.queryTimeSeriesRange(1L, "ts:cpu", "-inf", "+inf", "avg", 60_000L, 100);
        assertThat(range.supported()).isTrue();
        assertThat(range.totalLen()).isEqualTo(3L);
        assertThat(range.points()).hasSize(1);
        assertThat(range.reducedValue()).isEqualTo(55.25);

        assertThatThrownBy(() -> service.queryTimeSeriesRange(1L, "ts:cpu", "-inf", "+inf", "MEDIAN", 1000L, 10))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不支持的聚合函数");

        when(mockGateway.execute(instance, "TS.MRANGE", "-inf", "+inf"))
                .thenReturn(new RespReply.Error("ERR unknown command 'TS.MRANGE'"));
        assertThat(service.listTimeSeries(1L).supported()).isFalse();
    }

    @Test
    void jsonPathOperationsSupportReadWriteInspectAndValidation() throws Exception {
        when(mockGateway.execute(instance, "JSON.TYPE", "__tk_json_probe__"))
                .thenReturn(new RespReply.Array(List.of()));
        when(mockGateway.execute(instance, "SCAN", "0", "MATCH", "*", "COUNT", "50"))
                .thenReturn(new RespReply.Array(List.of(
                        bulk("0"),
                        new RespReply.Array(List.of(bulk("doc:user:1"), bulk("plain:key"))))));
        when(mockGateway.executeBatch(eq(instance), anyList()))
                .thenReturn(List.of(new RespReply.Simple("json"), new RespReply.Simple("string")));

        JsonKeysView jsonKeys = service.listJsonKeys(1L, "*", 50);
        assertThat(jsonKeys.supported()).isTrue();
        assertThat(jsonKeys.keys()).containsExactly("doc:user:1");

        when(mockGateway.execute(instance, "JSON.GET", "doc:user:1"))
                .thenReturn(bulk("{\"name\":\"Alice\",\"score\":10,\"tags\":[\"a\"]}"));
        when(mockGateway.execute(instance, "JSON.TYPE", "doc:user:1", "$"))
                .thenReturn(new RespReply.Array(List.of(bulk("object"))));
        when(mockGateway.execute(instance, "JSON.OBJKEYS", "doc:user:1", "$"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Array(List.of(bulk("name"), bulk("score"), bulk("tags"))))));

        JsonQueryView query = service.getJson(1L, "doc:user:1", "$");
        assertThat(query.supported()).isTrue();
        assertThat(query.exists()).isTrue();
        assertThat(query.valueType()).isEqualTo("object");
        assertThat(query.objectKeys()).containsExactly("name", "score", "tags");
        assertThat(query.prettyJson()).contains("\"name\"");

        when(mockGateway.execute(instance, "JSON.SET", "doc:user:1", "$.score", "20"))
                .thenReturn(new RespReply.Simple("OK"));
        service.setJson(new JsonSetRequest(1L, "doc:user:1", "$.score", "20"));

        assertThatThrownBy(() -> service.setJson(new JsonSetRequest(1L, "doc:user:1", "$", "{bad-json")))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("JSON 格式无效");

        when(mockGateway.execute(instance, "JSON.NUMINCRBY", "doc:user:1", "$.score", "5.0"))
                .thenReturn(bulk("25"));
        assertThat(service.numIncrJson(new JsonNumIncrRequest(1L, "doc:user:1", "$.score", 5.0))).isEqualTo("25");

        when(mockGateway.execute(instance, "JSON.ARRAPPEND", "doc:user:1", "$.tags", "\"b\""))
                .thenReturn(new RespReply.Array(List.of(new RespReply.Integer(2))));
        assertThat(service.arrAppendJson(new JsonArrAppendRequest(1L, "doc:user:1", "$.tags", "\"b\""))).isEqualTo(2L);

        when(mockGateway.execute(instance, "JSON.DEL", "doc:user:1", "$.score"))
                .thenReturn(new RespReply.Integer(1));
        assertThat(service.deleteJson(1L, "doc:user:1", "$.score")).isEqualTo(1L);
    }

    @Test
    void viewerForbiddenOnMutatingMultiModelEndpoints() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "viewer", "viewer123", Role.VIEWER);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer\",\"password\":\"viewer123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        String viewerToken = objectMapper.readTree(body).path("data").path("token").asText();

        Long instId = instanceRepository.findAll().stream().findFirst().map(DbInstance::getId).orElse(1L);

        mockMvc.perform(post("/api/v1/multimodel/vector/add")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":" + instId + ",\"id\":\"v1\",\"values\":[1,2]}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(post("/api/v1/multimodel/timeseries/add")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":" + instId + ",\"key\":\"ts:k\",\"value\":1.5}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(put("/api/v1/multimodel/json/set")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":" + instId + ",\"key\":\"j:k\",\"path\":\"$\",\"value\":\"{}\"}"))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/multimodel/json/del")
                        .param("instanceId", String.valueOf(instId))
                        .param("key", "j:k")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
    }
}
