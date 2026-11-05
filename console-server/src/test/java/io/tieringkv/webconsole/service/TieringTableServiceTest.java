package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.SstableTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** TD-099：SSTable 清单解析。 */
class TieringTableServiceTest {

    private InstanceService instanceService;
    private RespGateway gateway;
    private TieringTableService service;
    private DbInstance instance;

    private static String b64(String text) {
        return Base64.getEncoder().encodeToString(text.getBytes(StandardCharsets.UTF_8));
    }

    @BeforeEach
    void setUp() {
        instanceService = mock(InstanceService.class);
        gateway = mock(RespGateway.class);
        service = new TieringTableService(instanceService, gateway);
        instance = new DbInstance();
        instance.setId(1L);
        when(instanceService.require(1L)).thenReturn(instance);
    }

    @Test
    void parsesTablesAndTotals() throws IOException {
        String payload = "# SSTables\r\n"
                + "sstable_count:2\r\n"
                + "sstable_total_bytes:6144\r\n"
                + "sstable_total_entries:150\r\n"
                + "sstable:1|keys=50|bytes=2048|first_b64=" + b64("a:1") + "|last_b64=" + b64("a:50") + "\r\n"
                + "sstable:2|keys=100|bytes=4096|first_b64=" + b64("b:1") + "|last_b64=" + b64("b:99") + "\r\n";
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = service.list(1L);

        assertThat(view.count()).isEqualTo(2);
        assertThat(view.totalBytes()).isEqualTo(6144);
        assertThat(view.totalEntries()).isEqualTo(150);
        assertThat(view.tables()).extracting(SstableTable::id).containsExactly(1L, 2L);
        assertThat(view.tables().get(0).firstKey()).isEqualTo("a:1");
        assertThat(view.tables().get(1).lastKey()).isEqualTo("b:99");
    }

    @Test
    void emptyColdTierReturnsEmptyList() throws IOException {
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk("# SSTables\r\nsstable_count:0\r\n".getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = service.list(1L);

        assertThat(view.count()).isZero();
        assertThat(view.tables()).isEmpty();
    }

    @Test
    void unsupportedSectionSurfacesAsUpstreamError() throws IOException {
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Error("ERR unknown info section 'sstables'"));

        assertThatThrownBy(() -> service.list(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("INFO sstables 失败");
    }

    @Test
    void gatewayFailureSurfacesAsUpstreamError() throws IOException {
        when(gateway.execute(any(), any(String[].class)))
                .thenThrow(new IOException("connection refused"));

        assertThatThrownBy(() -> service.list(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("读取 SSTable 清单失败");
    }
}
