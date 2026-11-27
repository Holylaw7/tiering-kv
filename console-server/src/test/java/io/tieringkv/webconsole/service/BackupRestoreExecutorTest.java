package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.observability.ConsoleMetricsBinder;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespReply;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import tools.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.contains;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Wave 3B M2：BackupRestoreExecutor 协作者独立行为单测。 */
class BackupRestoreExecutorTest {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    @Test
    void returnsZeroWhenRecordMissing(@TempDir Path dir) {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        when(repo.findById(404L)).thenReturn(Optional.empty());
        BackupRestoreExecutor executor = newExecutor(dir, repo, mock(InstanceService.class),
                mock(RespGateway.class), mock(AuditService.class));

        assertThat(executor.runRestore(404L)).isZero();
    }

    @Test
    void replaysAllSupportedDataTypesAndRecordsSuccess(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        ConsoleMetricsBinder binder = mock(ConsoleMetricsBinder.class);

        DbInstance inst = instance(1L, "restore-node");
        when(instSvc.require(1L)).thenReturn(inst);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);
        String multiTypeData = "\n"
                + "{\"key\":null,\"type\":\"string\",\"ttl\":-1,\"value\":\"skip\"}\n"
                + "{\"key\":\"s1\",\"type\":\"string\",\"ttl\":30,\"value\":\"v1\"}\n"
                + "{\"key\":\"h1\",\"type\":\"hash\",\"ttl\":-1,\"value\":{\"f\":\"1\"}}\n"
                + "{\"key\":\"h0\",\"type\":\"hash\",\"ttl\":-1,\"value\":{}}\n"
                + "{\"key\":\"l1\",\"type\":\"list\",\"ttl\":-1,\"value\":[\"a\"]}\n"
                + "{\"key\":\"st1\",\"type\":\"set\",\"ttl\":-1,\"value\":[\"b\"]}\n"
                + "{\"key\":\"z1\",\"type\":\"zset\",\"ttl\":-1,\"value\":[{\"member\":\"m\",\"score\":\"1.5\"}]}\n"
                + "{\"key\":\"j1\",\"type\":\"json\",\"ttl\":-1,\"value\":{\"x\":1}}\n"
                + "{\"key\":\"u1\",\"type\":\"unknown\",\"ttl\":-1,\"value\":\"ignored\"}\n";
        publish(storage, "multi.jsonl", multiTypeData.getBytes(StandardCharsets.UTF_8));

        BackupRecord rec = new BackupRecord();
        rec.setId(27L);
        rec.setInstanceId(1L);
        rec.setFileName("multi.jsonl");
        when(repo.findById(27L)).thenReturn(Optional.of(rec));
        when(gateway.executeBatch(eq(inst), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            return cmds.stream().map(ignored -> (RespReply) new RespReply.Simple("OK")).toList();
        });

        BackupRestoreExecutor executor = new BackupRestoreExecutor(repo, instSvc, gateway, MAPPER, storage, audit);
        executor.setMetricsBinder(binder);

        long restored = executor.runRestore(27L);

        assertThat(restored).isEqualTo(9L);
        assertThat(rec.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);
        verify(binder).recordRestore("SUCCEEDED");
        verify(audit).record(eq("RESTORE_BACKUP"), eq("restore-node"), contains("restored=9"), eq(true));
    }

    @Test
    void preservesPartialCountOnBatchRespErrorAndParseError(@TempDir Path dir) throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instSvc = mock(InstanceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);

        DbInstance inst = instance(1L, "restore-err");
        when(instSvc.require(1L)).thenReturn(inst);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);
        String twoKeys = "{\"key\":\"s1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n"
                + "{\"key\":\"s2\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v2\"}\n";
        publish(storage, "err.jsonl", twoKeys.getBytes(StandardCharsets.UTF_8));

        BackupRecord rec = new BackupRecord();
        rec.setId(28L);
        rec.setInstanceId(1L);
        rec.setFileName("err.jsonl");
        when(repo.findById(28L)).thenReturn(Optional.of(rec));

        // 1. Mid-batch RESP Error on second key (index 2 = DEL s2)
        when(gateway.executeBatch(eq(inst), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            List<RespReply> reps = new ArrayList<>();
            for (int i = 0; i < cmds.size(); i++) {
                reps.add(i == 2 ? new RespReply.Error("ERR oom") : new RespReply.Simple("OK"));
            }
            return reps;
        });
        BackupRestoreExecutor executor = new BackupRestoreExecutor(repo, instSvc, gateway, MAPPER, storage, audit);
        long partial = executor.runRestore(28L);
        assertThat(partial).isEqualTo(1L);
        assertThat(rec.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(rec.getRestoreMessage()).contains("ERR oom");

        // 2. Reply count mismatch
        when(gateway.executeBatch(eq(inst), any())).thenReturn(List.of());
        assertThat(executor.runRestore(28L)).isZero();
        assertThat(rec.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
        assertThat(rec.getRestoreMessage()).contains("数量不匹配");

        // 3. Malformed line after a valid line flushes prior batch first
        String badLine = "{\"key\":\"k1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n{broken-json\n";
        publish(storage, "bad.jsonl", badLine.getBytes(StandardCharsets.UTF_8));
        rec.setFileName("bad.jsonl");
        when(gateway.executeBatch(eq(inst), any())).thenAnswer(inv -> {
            List<?> cmds = inv.getArgument(1);
            return cmds.stream().map(ignored -> (RespReply) new RespReply.Simple("OK")).toList();
        });
        assertThat(executor.runRestore(28L)).isEqualTo(1L);
        assertThat(rec.getRestoreStatus()).isEqualTo(RestoreStatus.FAILED);
    }

    private static BackupRestoreExecutor newExecutor(Path dir, BackupRecordRepository repo,
                                                     InstanceService instSvc, RespGateway gateway,
                                                     AuditService audit) {
        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(dir.toString());
        return new BackupRestoreExecutor(repo, instSvc, gateway, MAPPER,
                new LocalBackupStorageBackend(props), audit);
    }

    private static void publish(LocalBackupStorageBackend storage, String name, byte[] data) throws IOException {
        try (OutputStream out = storage.openTempOutput(name)) {
            out.write(data);
        }
        storage.publish(name);
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
