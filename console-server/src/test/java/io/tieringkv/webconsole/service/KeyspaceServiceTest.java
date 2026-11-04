package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** TD-089：键空间编辑的类型守卫与 hash 字段级操作。 */
class KeyspaceServiceTest {

    private InstanceService instanceService;
    private RespGateway gateway;
    private AuditService auditService;
    private KeyspaceService service;
    private DbInstance instance;

    @BeforeEach
    void setUp() {
        instanceService = mock(InstanceService.class);
        gateway = mock(RespGateway.class);
        auditService = mock(AuditService.class);
        service = new KeyspaceService(instanceService, gateway, auditService);
        instance = new DbInstance();
        instance.setName("local");
        when(instanceService.require(1L)).thenReturn(instance);
    }

    private void typeIs(String type) throws IOException {
        when(gateway.execute(eq(instance), eq("TYPE"), anyString()))
                .thenReturn(new RespReply.Simple(type));
    }

    @Test
    void setStringRejectsNonStringKey() throws IOException {
        typeIs("hash");

        assertThatThrownBy(() -> service.setString(1L, "h", "v"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能用 SET 覆盖");
        verify(gateway, never()).execute(eq(instance), eq("SET"), anyString(), anyString());
    }

    @Test
    void setStringAllowsMissingAndStringKeys() throws IOException {
        typeIs("none");
        when(gateway.execute(eq(instance), eq("SET"), anyString(), anyString()))
                .thenReturn(new RespReply.Simple("OK"));

        service.setString(1L, "k", "v");

        verify(gateway).execute(eq(instance), eq("SET"), eq("k"), eq("v"));
        verify(auditService).record(eq("KEY_SET"), anyString(), any(), eq(true));
    }

    @Test
    void setHashFieldCreatesFieldOnMissingKey() throws IOException {
        typeIs("none");
        when(gateway.execute(eq(instance), eq("HSET"), anyString(), anyString(), anyString()))
                .thenReturn(new RespReply.Integer(1));

        service.setHashField(1L, "h", "f", "v");

        verify(gateway).execute(eq(instance), eq("HSET"), eq("h"), eq("f"), eq("v"));
        verify(auditService).record(eq("KEY_HSET"), anyString(), any(), eq(true));
    }

    @Test
    void setHashFieldRejectsWrongType() throws IOException {
        typeIs("list");

        assertThatThrownBy(() -> service.setHashField(1L, "l", "f", "v"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不能写入 hash 字段");
    }

    @Test
    void deleteHashFieldReportsMissingField() throws IOException {
        typeIs("hash");
        when(gateway.execute(eq(instance), eq("HDEL"), anyString(), anyString()))
                .thenReturn(new RespReply.Integer(0));

        assertThatThrownBy(() -> service.deleteHashField(1L, "h", "absent"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("字段不存在");
    }

    @Test
    void deleteHashFieldRejectsNonHash() throws IOException {
        typeIs("string");

        assertThatThrownBy(() -> service.deleteHashField(1L, "s", "f"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("不是 hash");
    }

    @Test
    void deleteHashFieldDeletesAndAudits() throws IOException {
        typeIs("hash");
        when(gateway.execute(eq(instance), eq("HDEL"), anyString(), anyString()))
                .thenReturn(new RespReply.Integer(1));

        service.deleteHashField(1L, "h", "f");

        verify(gateway).execute(eq(instance), eq("HDEL"), eq("h"), eq("f"));
        verify(auditService).record(eq("KEY_HDEL"), anyString(), any(), eq(true));
    }

    @Test
    void auditRecordsInstanceScopedKey() throws IOException {
        typeIs("none");
        when(gateway.execute(eq(instance), eq("HSET"), anyString(), anyString(), anyString()))
                .thenReturn(new RespReply.Integer(1));

        service.setHashField(1L, "h", "f", "v");

        verify(auditService).record(eq("KEY_HSET"), eq("local/h/f"), any(), eq(true));
    }
}
