package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.KeyEntry;
import io.tieringkv.webconsole.web.dto.KeyScanPage;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyList;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** O2-A：KeyspaceService 扫描与多类型批量读取走单次 Pipeline 往返验证。 */
class KeyspaceServicePipelineTest {

    private RespGateway gateway;
    private KeyspaceService service;
    private DbInstance instance;

    @BeforeEach
    void setUp() {
        InstanceService instanceService = mock(InstanceService.class);
        gateway = mock(RespGateway.class);
        AuditService auditService = mock(AuditService.class);
        service = new KeyspaceService(instanceService, gateway, auditService);
        instance = new DbInstance();
        instance.setId(1L);
        instance.setName("local");
        when(instanceService.require(1L)).thenReturn(instance);
    }

    @Test
    void scan50KeysUsesSinglePipelineBatchForTypeAndTtl() throws Exception {
        List<RespReply> scanKeys = new ArrayList<>();
        List<RespReply> batchReplies = new ArrayList<>();
        for (int i = 0; i < 50; i++) {
            scanKeys.add(new RespReply.Bulk(("k:" + i).getBytes(StandardCharsets.UTF_8)));
            batchReplies.add(new RespReply.Simple("string"));
            batchReplies.add(new RespReply.Integer(i == 0 ? -1 : 60 + i));
        }
        when(gateway.execute(instance, "SCAN", "0", "MATCH", "*", "COUNT", "50"))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(scanKeys))));
        when(gateway.executeBatch(eq(instance), anyList())).thenReturn(batchReplies);

        KeyScanPage page = service.scan(1L, "0", "*", 50);

        assertThat(page.finished()).isTrue();
        assertThat(page.keys()).hasSize(50);
        assertThat(page.keys().get(0)).isEqualTo(new KeyEntry("k:0", "string", -1));
        assertThat(page.keys().get(49)).isEqualTo(new KeyEntry("k:49", "string", 109));
        verify(gateway, times(1)).executeBatch(eq(instance), anyList());
    }

    @Test
    @SuppressWarnings("unchecked")
    void readValuesBatchDecodesAllStandardTypesInOneRoundTrip() throws Exception {
        List<KeyEntry> metas = List.of(
                new KeyEntry("s", "string", -1),
                new KeyEntry("h", "hash", 30),
                new KeyEntry("l", "list", -1),
                new KeyEntry("z", "zset", -1),
                new KeyEntry("j", "json", -1)
        );
        when(gateway.executeBatch(eq(instance), anyList())).thenReturn(List.of(
                new RespReply.Bulk("hello".getBytes(StandardCharsets.UTF_8)),
                new RespReply.Array(List.of(
                        new RespReply.Bulk("f1".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("v1".getBytes(StandardCharsets.UTF_8)))),
                new RespReply.Array(List.of(
                        new RespReply.Bulk("a".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("b".getBytes(StandardCharsets.UTF_8)))),
                new RespReply.Array(List.of(
                        new RespReply.Bulk("m1".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Bulk("1.5".getBytes(StandardCharsets.UTF_8)))),
                new RespReply.Bulk("{\"ok\":true}".getBytes(StandardCharsets.UTF_8))
        ));

        List<Object> values = service.readValuesBatch(instance, metas);

        assertThat(values).hasSize(5);
        assertThat(values.get(0)).isEqualTo("hello");
        assertThat((Map<String, String>) values.get(1)).containsEntry("f1", "v1");
        assertThat((List<String>) values.get(2)).containsExactly("a", "b");
        assertThat((List<Map<String, String>>) values.get(3)).containsExactly(Map.of("member", "m1", "score", "1.5"));
        assertThat(values.get(4)).isEqualTo("{\"ok\":true}");
        verify(gateway, times(1)).executeBatch(eq(instance), any());
    }
}
