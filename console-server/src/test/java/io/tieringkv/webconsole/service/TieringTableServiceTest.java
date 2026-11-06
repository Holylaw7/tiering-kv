package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.web.dto.SstableTable;
import com.fasterxml.jackson.databind.ObjectMapper;
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
                + "sstable_key_order:bytewise\r\n"
                + "sstable_count:2\r\n"
                + "sstable_total_bytes:6144\r\n"
                + "sstable_total_entries:150\r\n"
                + "sstable:1|keys=50|bytes=2048|first_b64=" + b64("a:1") + "|last_b64=" + b64("a:50")
                + "|first_text=a:1|last_text=a:50\r\n"
                + "sstable:2|keys=100|bytes=4096|first_b64=" + b64("b:1") + "|last_b64=" + b64("b:99")
                + "|first_text=b:1|last_text=b:99\r\n";
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = service.list(1L);

        assertThat(view.keyOrder()).isEqualTo("bytewise");
        assertThat(view.count()).isEqualTo(2);
        assertThat(view.totalBytes()).isEqualTo(6144);
        assertThat(view.totalEntries()).isEqualTo(150);
        assertThat(view.tables()).extracting(SstableTable::id).containsExactly(1L, 2L);
        assertThat(view.tables().get(0).firstKey()).isEqualTo("a:1");
        assertThat(view.tables().get(1).lastKey()).isEqualTo("b:99");
        assertThat(view.tables().get(0).firstKeyBase64()).isEqualTo(b64("a:1"));
    }

    @Test
    void fallsBackToBase64WhenKernelOmitsTextBounds() throws IOException {
        String payload = "# SSTables\r\n"
                + "sstable_count:1\r\n"
                + "sstable:9|keys=1|bytes=1|first_b64=" + b64("k:1") + "|last_b64=" + b64("k:2") + "\r\n";
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = service.list(1L);

        assertThat(view.keyOrder()).isEqualTo("unknown");
        assertThat(view.tables().get(0).firstKey()).isEqualTo("k:1");
        assertThat(view.tables().get(0).lastKey()).isEqualTo("k:2");
    }

    @Test
    void keepsEscapedBinaryBoundsAsTextAndRawBytesAsBase64() throws IOException {
        String payload = "# SSTables\r\n"
                + "sstable_key_order:bytewise\r\n"
                + "sstable_count:1\r\n"
                + "sstable:1|keys=1|bytes=1|first_b64=" + b64("\\u0000\\u0001") + "|last_b64=" + b64("z")
                + "|first_text=\\x00\\x01|last_text=z\r\n";
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = service.list(1L);

        assertThat(view.tables().get(0).firstKey()).isEqualTo("\\x00\\x01");
        assertThat(view.tables().get(0).firstKeyBase64()).isEqualTo(b64("\\u0000\\u0001"));
    }

    @Test
    void handlesOneThousandTablesWithinBudget() throws Exception {
        StringBuilder payload = new StringBuilder("# SSTables\r\n")
                .append("sstable_key_order:bytewise\r\n")
                .append("sstable_count:1000\r\n");
        long totalBytes = 0;
        for (int i = 1; i <= 1000; i++) {
            long bytes = 16_384L * i;
            totalBytes += bytes;
            payload.append("sstable:").append(i)
                    .append("|keys=").append(100 * i)
                    .append("|bytes=").append(bytes)
                    .append("|first_b64=").append(b64("tier:" + i))
                    .append("|last_b64=").append(b64("tier:" + (i + 1)))
                    .append("|first_text=tier:").append(i)
                    .append("|last_text=tier:").append(i + 1)
                    .append("\r\n");
        }
        payload.append("sstable_total_bytes:").append(totalBytes).append("\r\n");
        payload.append("sstable_total_entries:").append(100L * 1001 * 1000 / 2).append("\r\n");
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.toString().getBytes(StandardCharsets.UTF_8)));

        long started = System.nanoTime();
        SstableTable.View view = service.list(1L);
        long parseMicros = (System.nanoTime() - started) / 1_000;

        assertThat(view.count()).isEqualTo(1000);
        assertThat(view.tables()).hasSize(1000);
        assertThat(view.tables().get(999).id()).isEqualTo(1000L);
        assertThat(view.totalBytes()).isEqualTo(totalBytes);

        ObjectMapper mapper = new ObjectMapper();
        String json = mapper.writeValueAsString(view);
        System.out.printf("[inventory-api] tables=1000 parse=%dus json=%dB kernelText=%dB%n",
                parseMicros, json.length(), payload.length());

        assertThat(json.length()).isLessThan(400_000);
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