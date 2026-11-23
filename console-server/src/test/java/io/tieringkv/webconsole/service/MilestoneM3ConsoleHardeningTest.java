package io.tieringkv.webconsole.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.config.ProductionConfigGuard;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.resp.RespClient;
import io.tieringkv.webconsole.resp.RespClientPool;
import io.tieringkv.webconsole.resp.RespException;
import io.tieringkv.webconsole.resp.RespReply;
import io.tieringkv.webconsole.security.InstanceCredentialCipher;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.mock.env.MockEnvironment;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

/**
 * 里程碑 M3（B2 流式压缩备份 + B3 连接池配额、静态加密与 SpringDoc 生产巡检）回归测试。
 */
class MilestoneM3ConsoleHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void credentialCipherEncryptsDecryptsAndPreservesLegacyPlaintext() {
        InstanceCredentialCipher cipher = new InstanceCredentialCipher("master-key-for-test-only");
        String encrypted = cipher.encrypt("redis-pass-123");
        assertThat(encrypted).startsWith(InstanceCredentialCipher.PREFIX);
        assertThat(encrypted).doesNotContain("redis-pass-123");
        assertThat(cipher.decrypt(encrypted)).isEqualTo("redis-pass-123");
        // 幂等加密 + 历史明文透传
        assertThat(cipher.encrypt(encrypted)).isEqualTo(encrypted);
        assertThat(cipher.decrypt("legacy-plain")).isEqualTo("legacy-plain");

        InstanceCredentialCipher wrongKey = new InstanceCredentialCipher("other-master-key");
        assertThatThrownBy(() -> wrongKey.decrypt(encrypted))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void respClientPoolEnforcesMaxTotalDiscardPermitReleaseAndIdleExpiry() throws Exception {
        AtomicInteger created = new AtomicInteger();
        RespClientPool pool = new RespClientPool(() -> {
            created.incrementAndGet();
            RespClient c = mock(RespClient.class);
            when(c.isUsable()).thenReturn(true);
            return c;
        }, 2, 2, 100, 30);

        RespClient c1 = pool.borrow();
        RespClient c2 = pool.borrow();
        assertThat(pool.activeCount()).isEqualTo(2);

        // 达到 maxTotal=2 后借用超时
        assertThatThrownBy(pool::borrow)
                .isInstanceOf(RespException.class)
                .hasMessageContaining("maxTotal=2");

        // discard 正确释放配额，防止连接异常泄露许可
        pool.discard(c1);
        assertThat(pool.activeCount()).isEqualTo(1);
        RespClient c3 = pool.borrow();
        assertThat(pool.activeCount()).isEqualTo(2);

        pool.release(c2);
        pool.release(c3);
        assertThat(pool.idleCount()).isEqualTo(2);

        // 等待超过 maxIdleTimeMs=30ms，过期空闲连接会被驱逐并重建
        Thread.sleep(60);
        RespClient fresh = pool.borrow();
        assertThat(created.get()).isEqualTo(4);
        pool.release(fresh);
        pool.close();
    }

    @Test
    void backupServiceExportsGzipAndRestoresBothCompressedAndPlainJsonl() throws Exception {
        BackupRecordRepository repo = mock(BackupRecordRepository.class);
        InstanceService instanceService = mock(InstanceService.class);
        KeyspaceService keyspaceService = mock(KeyspaceService.class);
        RespGateway gateway = mock(RespGateway.class);
        AuditService audit = mock(AuditService.class);
        BackupCapacityService capacity = mock(BackupCapacityService.class);

        Map<Long, BackupRecord> db = new ConcurrentHashMap<>();
        when(repo.save(any(BackupRecord.class))).thenAnswer(inv -> {
            BackupRecord r = inv.getArgument(0);
            if (r.getId() == null) {
                r.setId((long) (db.size() + 1));
            }
            db.put(r.getId(), r);
            return r;
        });
        when(repo.findById(any())).thenAnswer(inv -> Optional.ofNullable(db.get(inv.getArgument(0))));

        DbInstance inst = new DbInstance();
        inst.setId(1L);
        inst.setName("local");
        when(instanceService.require(1L)).thenReturn(inst);

        when(gateway.execute(eq(inst), eq("SCAN"), eq("0"), eq("MATCH"), eq("*"), eq("COUNT"), eq("500")))
                .thenReturn(new RespReply.Array(List.of(
                        new RespReply.Bulk("0".getBytes(StandardCharsets.UTF_8)),
                        new RespReply.Array(List.of(
                                new RespReply.Bulk("k1".getBytes(StandardCharsets.UTF_8)),
                                new RespReply.Bulk("k2".getBytes(StandardCharsets.UTF_8)))))));
        when(keyspaceService.type(eq(inst), any())).thenReturn("string");
        when(keyspaceService.ttl(eq(inst), any())).thenReturn(-1L);
        when(keyspaceService.value(eq(inst), eq("k1"), eq("string"))).thenReturn("v1");
        when(keyspaceService.value(eq(inst), eq("k2"), eq("string"))).thenReturn("v2");
        when(gateway.execute(eq(inst), eq("DEL"), any())).thenReturn(new RespReply.Integer(1));
        when(gateway.execute(eq(inst), eq("SET"), any(), any())).thenReturn(new RespReply.Simple("OK"));

        ConsoleProperties props = new ConsoleProperties();
        Path backupDir = tempDir.resolve("backups");
        props.getBackup().setDir(backupDir.toString());
        props.getBackup().setCompress(true);
        props.getBackup().setMaxKeys(0);

        BackupService service = new BackupService(repo, instanceService, keyspaceService, gateway,
                new ObjectMapper(), props, new LocalBackupStorageBackend(backupDir),
                Runnable::run, audit, capacity);

        BackupRecord created = service.export(1L);
        assertThat(created.getFileName()).endsWith(".jsonl.gz");
        assertThat(created.getStatus()).isEqualTo(BackupStatus.SUCCESS);
        assertThat(created.getKeyCount()).isEqualTo(2);
        byte[] rawBytes = Files.readAllBytes(backupDir.resolve(created.getFileName()));
        assertThat(rawBytes[0] & 0xff).isEqualTo(0x1f);
        assertThat(rawBytes[1] & 0xff).isEqualTo(0x8b);

        // 压缩包可透明恢复
        assertThat(service.restore(created.getId())).isEqualTo(2L);
        assertThat(created.getRestoreStatus()).isEqualTo(RestoreStatus.SUCCEEDED);

        // 历史明文 .jsonl 同样可透明恢复
        Path plainFile = backupDir.resolve("legacy-plain.jsonl");
        Files.writeString(plainFile, "{\"key\":\"k1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n");
        BackupRecord legacy = new BackupRecord();
        legacy.setId(99L);
        legacy.setInstanceId(1L);
        legacy.setFileName("legacy-plain.jsonl");
        legacy.setStatus(BackupStatus.SUCCESS);
        legacy.setCreatedAt(LocalDateTime.now());
        db.put(99L, legacy);
        assertThat(service.restore(99L)).isEqualTo(1L);
    }

    @Test
    void productionGuardWarnsWhenSpringDocLeftEnabled() {
        ConsoleProperties props = new ConsoleProperties();
        props.getJwt().setSecret("Zk8Qv2LmN7pR4tXyA9bC3dE6fG1hJ5kM0nP8sU2w");
        props.getBootstrap().setAdminPassword("AdminPass!2345");
        MockEnvironment env = new MockEnvironment()
                .withProperty("spring.datasource.password", "S3cure-db-pass")
                .withProperty("spring.datasource.username", "console_app")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/tiering_console");

        ProductionConfigGuard guardDefault = new ProductionConfigGuard(props, env);
        assertThat(guardDefault.check())
                .anyMatch(f -> "WARN".equals(f.level()) && "springdoc".equals(f.key()));

        env.setProperty("springdoc.api-docs.enabled", "false");
        env.setProperty("springdoc.swagger-ui.enabled", "false");
        ProductionConfigGuard guardHardened = new ProductionConfigGuard(props, env);
        assertThat(guardHardened.check())
                .noneMatch(f -> "springdoc".equals(f.key()));
    }
}
