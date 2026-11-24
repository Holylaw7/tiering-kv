package io.tieringkv.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespException;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.security.LoginAttemptGuard;
import io.tieringkv.webconsole.web.dto.SstableTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

class Stage4ServiceHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void tieringTableServiceParsesKeysContainingPipeCharacter() throws IOException {
        InstanceService instanceService = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        DbInstance instance = new DbInstance();
        instance.setId(1L);
        when(instanceService.require(1L)).thenReturn(instance);

        String k1 = "user|alpha|1";
        String k2 = "user|beta|2";
        String payload = "# SSTables\r\n"
                + "sstable_key_order:bytewise\r\n"
                + "sstable_count:1\r\n"
                + "sstable:7|keys=12|bytes=4096"
                + "|first_b64=" + Base64.getEncoder().encodeToString(k1.getBytes(StandardCharsets.UTF_8))
                + "|last_b64=" + Base64.getEncoder().encodeToString(k2.getBytes(StandardCharsets.UTF_8))
                + "|first_text=" + k1
                + "|last_text=" + k2 + "\r\n";
        when(gateway.execute(instance, "INFO", "sstables"))
                .thenReturn(new RespReply.Bulk(payload.getBytes(StandardCharsets.UTF_8)));

        SstableTable.View view = new TieringTableService(instanceService, gateway).list(1L);
        assertThat(view.tables()).singleElement().satisfies(t -> {
            assertThat(t.id()).isEqualTo(7L);
            assertThat(t.keys()).isEqualTo(12L);
            assertThat(t.bytes()).isEqualTo(4096L);
            assertThat(t.firstKey()).isEqualTo(k1);
            assertThat(t.lastKey()).isEqualTo(k2);
        });
    }

    @Test
    void backupRestoreFailsOnRespErrorBlocksPathTraversalAndSerializesJson() throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instanceService = mock(InstanceService.class);
        KeyspaceService keyspace = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        BackupCapacityService capacity = mock(BackupCapacityService.class);

        ConsoleProperties props = new ConsoleProperties();
        Path backupDir = Files.createDirectories(tempDir.resolve("backups"));
        props.getBackup().setDir(backupDir.toString());

        DbInstance instance = new DbInstance();
        instance.setId(1L);
        instance.setName("local");
        when(instanceService.require(1L)).thenReturn(instance);
        when(repo.save(any(BackupRecord.class))).thenAnswer(inv -> inv.getArgument(0));

        BackupService service = new BackupService(repo, instanceService, keyspace, gateway,
                new ObjectMapper(), props, Runnable::run, audit, capacity);

        // Path traversal check
        BackupRecord traversal = new BackupRecord();
        traversal.setId(1L);
        traversal.setInstanceId(1L);
        traversal.setStatus(BackupStatus.SUCCESS);
        traversal.setFileName("../outside.jsonl");
        when(repo.findById(1L)).thenReturn(Optional.of(traversal));
        assertThatThrownBy(() -> service.restore(1L))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("非法备份路径");

        // Error propagation + JSON object serialization
        BackupRecord jsonAndErr = new BackupRecord();
        jsonAndErr.setId(2L);
        jsonAndErr.setInstanceId(1L);
        jsonAndErr.setStatus(BackupStatus.SUCCESS);
        jsonAndErr.setFileName("backup-ok.jsonl");
        Files.writeString(backupDir.resolve("backup-ok.jsonl"),
                "{\"key\":\"doc:1\",\"type\":\"json\",\"ttl\":0,\"value\":{\"a\":1}}\n"
                        + "{\"key\":\"bad:1\",\"type\":\"string\",\"ttl\":0,\"value\":\"v\"}\n",
                StandardCharsets.UTF_8);
        when(repo.findById(2L)).thenReturn(Optional.of(jsonAndErr));

        List<List<String>> captured = new java.util.ArrayList<>();
        when(gateway.executeBatch(eq(instance), any())).thenAnswer(inv -> {
            @SuppressWarnings("unchecked")
            List<List<byte[]>> cmds = inv.getArgument(1);
            for (List<byte[]> cmd : cmds) {
                captured.add(cmd.stream().map(b -> new String(b, StandardCharsets.UTF_8)).toList());
            }
            return List.of(
                    new RespReply.Integer(1),
                    new RespReply.Simple("OK"),
                    new RespReply.Integer(1),
                    new RespReply.Error("OOM command not allowed")
            );
        });

        service.restore(2L);
        assertThat(captured).contains(List.of("JSON.SET", "doc:1", "$", "{\"a\":1}"));
        assertThat(jsonAndErr.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(jsonAndErr.getRestoredKeys()).isEqualTo(1L);
        assertThat(jsonAndErr.getRestoreMessage())
                .contains("SET 失败")
                .contains("已回放 1 个键");
    }

    @Test
    void loginAttemptGuardCapsTrackedAccounts() {
        LoginAttemptGuard guard = new LoginAttemptGuard();
        for (int i = 0; i < 10_500; i++) {
            guard.recordFailure("user-" + i);
        }
        assertThat(guard.blocked("user-10499")).isFalse();
        for (int i = 0; i < 4; i++) {
            guard.recordFailure("user-10499");
        }
        assertThat(guard.blocked("user-10499")).isTrue();
    }

    @Test
    void respGatewayDiscardsBrokenSocketOnRespExceptionAndSurfacesUnsupportedAuth() throws Exception {
        ConsoleProperties props = new ConsoleProperties();
        props.getMetrics().setConnectTimeoutMs(1000);
        props.getMetrics().setReadTimeoutMs(1000);

        AtomicInteger acceptedConnections = new AtomicInteger();
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread serverThread = Thread.startVirtualThread(() -> {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        int n = acceptedConnections.incrementAndGet();
                        byte[] buf = new byte[512];
                        s.getInputStream().read(buf);
                        OutputStream out = s.getOutputStream();
                        if (n == 1) {
                            // First connection returns malformed RESP prefix -> RespException
                            out.write("!invalid\r\n".getBytes(StandardCharsets.UTF_8));
                        } else {
                            // Second connection checks clean recovery
                            out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                        }
                        out.flush();
                    } catch (IOException ignored) {
                    }
                }
            });

            RespGateway gateway = new RespGateway(props);
            DbInstance instance = new DbInstance();
            instance.setId(99L);
            instance.setHost("127.0.0.1");
            instance.setPort(ss.getLocalPort());

            assertThatThrownBy(() -> gateway.execute(instance, "PING"))
                    .isInstanceOf(RespException.class);

            // Second execute must open a fresh connection rather than reusing the corrupted socket
            RespReply second = gateway.execute(instance, "PING");
            assertThat(second).isEqualTo(new RespReply.Simple("PONG"));
            assertThat(acceptedConnections.get()).isEqualTo(2);
            gateway.shutdown();
            serverThread.interrupt();
        }
    }
}
