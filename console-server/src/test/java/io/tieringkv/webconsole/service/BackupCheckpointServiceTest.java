package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.charset.StandardCharsets;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/** Wave 3B M2：BackupCheckpointService 协作者独立行为单测（BGSAVE / LASTSAVE / INFO persistence）。 */
class BackupCheckpointServiceTest {

    @Test
    void handlesAllTriggerCheckpointResponses() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        DbInstance inst = instance(1L, "ckpt-node");
        when(instSvc.require(1L)).thenReturn(inst);

        BackupCheckpointService svc = new BackupCheckpointService(instSvc, gateway, audit);

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Simple("Background saving started"));
        when(gateway.execute(inst, "LASTSAVE")).thenReturn(new RespReply.Integer(1700000000L));
        var ok = svc.triggerCheckpoint(1L);
        assertThat(ok.triggered()).isTrue();
        assertThat(ok.lastSaveTime()).isEqualTo(1700000000L);

        when(gateway.execute(inst, "BGSAVE"))
                .thenReturn(new RespReply.Error("ERR Background save already in progress"));
        assertThat(svc.triggerCheckpoint(1L).triggered()).isFalse();

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Error("ERR unknown command 'BGSAVE'"));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("不支持 BGSAVE");

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Error("ERR disk full"));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("触发物理快照失败");

        when(gateway.execute(inst, "BGSAVE")).thenReturn(new RespReply.Integer(1));
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("异常响应类型");

        doThrow(new IOException("reset")).when(gateway).execute(inst, "BGSAVE");
        assertThatThrownBy(() -> svc.triggerCheckpoint(1L))
                .isInstanceOf(BusinessException.class).hasMessageContaining("触发 BGSAVE 失败");
    }

    @Test
    void parsesCheckpointStatusAndDegradesGracefully() throws Exception {
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        DbInstance inst = instance(1L, "ckpt-status-node");
        when(instSvc.require(1L)).thenReturn(inst);

        BackupCheckpointService svc = new BackupCheckpointService(instSvc, gateway, audit);

        doReturn(new RespReply.Error("ERR unknown command")).when(gateway).execute(inst, "LASTSAVE");
        assertThat(svc.checkpointStatus(1L).supported()).isFalse();

        doReturn(new RespReply.Error("ERR auth")).when(gateway).execute(inst, "LASTSAVE");
        assertThatThrownBy(() -> svc.checkpointStatus(1L)).isInstanceOf(BusinessException.class);

        doThrow(new IOException("io")).when(gateway).execute(inst, "LASTSAVE");
        assertThatThrownBy(() -> svc.checkpointStatus(1L)).isInstanceOf(BusinessException.class);

        doReturn(new RespReply.Integer(100L)).when(gateway).execute(inst, "LASTSAVE");
        when(gateway.execute(inst, "INFO", "persistence")).thenReturn(new RespReply.Bulk((
                "# Persistence\r\nrdb_bgsave_in_progress:1\r\nrdb_last_save_time:200\r\n"
                        + "rdb_last_bgsave_status:ok\r\nrdb_last_bgsave_time_sec:bad\r\n"
                        + "rdb_last_bgsave_duration_ms:150\r\n").getBytes(StandardCharsets.UTF_8)));
        var cs = svc.checkpointStatus(1L);
        assertThat(cs.supported()).isTrue();
        assertThat(cs.inProgress()).isTrue();
        assertThat(cs.lastSaveTime()).isEqualTo(200L);
        assertThat(cs.lastDurationSeconds()).isEqualTo(-1L);
        assertThat(cs.lastDurationMillis()).isEqualTo(150L);

        when(gateway.execute(inst, "INFO", "persistence")).thenThrow(new IOException("ignored"));
        assertThat(svc.checkpointStatus(1L).lastSaveTime()).isEqualTo(100L);
    }

    private static DbInstance instance(Long id, String name) {
        DbInstance inst = new DbInstance();
        inst.setId(id);
        inst.setName(name);
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        return inst;
    }
}
