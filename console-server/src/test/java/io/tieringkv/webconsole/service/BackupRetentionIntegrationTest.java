package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalDateTime;
import java.util.UUID;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 Spring + H2 上下文验证 {@link BackupRetentionService#scheduledPurge()} 不会因自调用绕过事务而抛出
 * {@code TransactionRequiredException}。
 */
@SpringBootTest
@ActiveProfiles("test")
class BackupRetentionIntegrationTest {

    @Autowired
    private BackupRetentionService retentionService;

    @Autowired
    private BackupRecordRepository backupRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private ConsoleProperties properties;

    @Autowired
    private ConsoleLeaseRepository leaseRepository;

    private Long createdInstanceId;
    private Long keptRecordId;

    @BeforeEach
    void resetRetentionLease() {
        leaseRepository.deleteById(BackupRetentionService.BACKUP_RETENTION_LEASE_KEY);
    }

    @AfterEach
    void cleanUp() {
        if (keptRecordId != null) {
            backupRepository.findById(keptRecordId).ifPresent(r -> {
                Path f = Paths.get(properties.getBackup().getDir()).resolve(r.getFileName());
                try {
                    Files.deleteIfExists(f);
                } catch (IOException ignored) {
                }
                backupRepository.deleteById(keptRecordId);
            });
        }
        if (createdInstanceId != null) {
            instanceRepository.deleteById(createdInstanceId);
        }
    }

    @Test
    void scheduledPurgeRunsWithinRealTransactionAndRetiresExcessBackup() throws IOException {
        Path backupDir = Paths.get(properties.getBackup().getDir()).toAbsolutePath().normalize();
        Files.createDirectories(backupDir);

        DbInstance inst = new DbInstance();
        inst.setName("retention-tx-" + UUID.randomUUID());
        inst.setHost("127.0.0.1");
        inst.setPort(6379);
        inst.setBackupRetention(1);
        inst = instanceRepository.save(inst);
        createdInstanceId = inst.getId();

        LocalDateTime now = LocalDateTime.now();
        String oldFileName = "old-" + UUID.randomUUID() + ".jsonl";
        String newFileName = "new-" + UUID.randomUUID() + ".jsonl";
        Path oldFile = backupDir.resolve(oldFileName);
        Path newFile = backupDir.resolve(newFileName);
        Files.writeString(oldFile, "{}\n");
        Files.writeString(newFile, "{}\n");

        BackupRecord staleRetiring = new BackupRecord();
        staleRetiring.setInstanceId(inst.getId());
        staleRetiring.setFileName(oldFileName);
        staleRetiring.setSizeBytes(3);
        staleRetiring.setKeyCount(1);
        staleRetiring.setStatus(BackupStatus.SUCCESS);
        staleRetiring.setCreatedAt(now.minusMinutes(120));
        staleRetiring.setFinishedAt(now.minusMinutes(119));
        staleRetiring.setDeleteState("RETIRING");
        staleRetiring.setDeleteClaimedAt(now.minusMinutes(100));
        staleRetiring = backupRepository.save(staleRetiring);

        BackupRecord newest = new BackupRecord();
        newest.setInstanceId(inst.getId());
        newest.setFileName(newFileName);
        newest.setSizeBytes(3);
        newest.setKeyCount(1);
        newest.setStatus(BackupStatus.SUCCESS);
        newest.setCreatedAt(now.minusMinutes(10));
        newest.setFinishedAt(now.minusMinutes(9));
        newest = backupRepository.save(newest);
        keptRecordId = newest.getId();

        // 直接以调度入口无外层事务调用，验证 releaseStaleClaims 与 claimDeletion 均在事务内提交
        retentionService.scheduledPurge();

        BackupRetentionService.PurgeReport report = retentionService.view().lastReport();
        assertThat(report).isNotNull();
        assertThat(report.failed()).isZero();
        assertThat(report.deleted()).isGreaterThanOrEqualTo(1);
        assertThat(backupRepository.findById(staleRetiring.getId())).isEmpty();
        assertThat(backupRepository.findById(newest.getId())).isPresent();
        assertThat(Files.exists(oldFile)).isFalse();
        assertThat(Files.exists(newFile)).isTrue();
    }
}
