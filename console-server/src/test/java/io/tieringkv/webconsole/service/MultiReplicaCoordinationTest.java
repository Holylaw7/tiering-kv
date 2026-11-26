package io.tieringkv.webconsole.service;

import tools.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.BootstrapDataInitializer;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.MetricPointRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.PlatformTransactionManager;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** M3：控制面双副本跨节点备份互斥与定时采样选主验证。 */
@SpringBootTest
@ActiveProfiles("test")
class MultiReplicaCoordinationTest {

    @TempDir
    Path tempDir;

    @Autowired
    private ConsoleLeaseRepository leaseRepository;

    @Autowired
    private BackupRecordRepository backupRecordRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private ObjectMapper objectMapper;

    private AtomicReference<Instant> currentInstant;
    private Clock mutableClock;
    private DbInstance testInstance;

    @BeforeEach
    void setUp() {
        leaseRepository.deleteAll();
        backupRecordRepository.deleteAll();
        currentInstant = new AtomicReference<>(Instant.parse("2026-11-25T10:00:00Z"));
        mutableClock = new Clock() {
            @Override
            public ZoneId getZone() {
                return ZoneId.of("UTC");
            }

            @Override
            public Clock withZone(ZoneId zone) {
                return this;
            }

            @Override
            public Instant instant() {
                return currentInstant.get();
            }
        };
        testInstance = instanceRepository.findByName("m3-ha-inst").orElseGet(() -> {
            DbInstance inst = new DbInstance();
            inst.setName("m3-ha-inst");
            inst.setHost("127.0.0.1");
            inst.setPort(6379);
            inst.setEnabled(true);
            return instanceRepository.save(inst);
        });
    }

    @Test
    void rejectsExportAndRestoreWith409WhenPeerReplicaHoldsInstanceLease() throws Exception {
        DbLeaseLockService replicaA = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-a", mutableClock);
        DbLeaseLockService replicaB = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-b", mutableClock);

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().setDir(tempDir.toString());
        LocalBackupStorageBackend storage = new LocalBackupStorageBackend(props);

        InstanceService instanceService = mock(InstanceService.class);
        when(instanceService.require(testInstance.getId())).thenReturn(testInstance);
        BackupCapacityService capacityService = mock(BackupCapacityService.class);
        when(capacityService.estimate(anyLong())).thenReturn(1024L);

        BackupService serviceB = new BackupService(
                backupRecordRepository,
                instanceService,
                mock(KeyspaceService.class),
                mock(RespGateway.class),
                objectMapper,
                props,
                storage,
                runnable -> { },
                mock(AuditService.class),
                capacityService,
                replicaB);

        // 模拟副本 A 正在临界区提交同一实例任务
        String leaseKey = "backup-op:instance:" + testInstance.getId();
        assertThat(replicaA.tryAcquire(leaseKey, Duration.ofSeconds(15))).isTrue();

        // 副本 B 提交导出必须立即 409 冲突
        assertThatThrownBy(() -> serviceB.export(testInstance.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(409));

        // 准备一份已成功的备份文件，验证副本 B 发起恢复同样被跨副本锁拦成 409
        Files.writeString(tempDir.resolve("m3-sample.jsonl"), "");
        BackupRecord existing = new BackupRecord();
        existing.setInstanceId(testInstance.getId());
        existing.setFileName("m3-sample.jsonl");
        existing.setStatus(BackupStatus.SUCCESS);
        existing.setRestoreStatus(RestoreStatus.NONE);
        existing.setKeyCount(0);
        existing.setSizeBytes(0);
        existing.setCreatedAt(LocalDateTime.now());
        BackupRecord saved = backupRecordRepository.save(existing);

        assertThatThrownBy(() -> serviceB.restore(saved.getId()))
                .isInstanceOf(BusinessException.class)
                .satisfies(ex -> assertThat(((BusinessException) ex).getStatus()).isEqualTo(409));

        // 副本 A 释放临界区租约后，副本 B 恢复立即受理成功并自动释放租约
        replicaA.release(leaseKey);
        BackupRecord restoring = serviceB.restore(saved.getId());
        assertThat(restoring.getRestoreStatus()).isEqualTo(RestoreStatus.RESTORING);
        assertThat(leaseRepository.existsById(leaseKey)).isFalse();
    }

    @Test
    void scheduledMonitorCollectsOnlyOnLeaderReplicaAndFailsOverOnExpiry() {
        DbLeaseLockService leaderLease = new DbLeaseLockService(
                leaseRepository, transactionManager, "monitor-a", mutableClock);
        DbLeaseLockService standbyLease = new DbLeaseLockService(
                leaseRepository, transactionManager, "monitor-b", mutableClock);

        InstanceService instanceService = mock(InstanceService.class);
        when(instanceService.enabled()).thenReturn(List.of(testInstance));
        RespGateway gateway = mock(RespGateway.class);
        when(gateway.info(any())).thenReturn("# Server\r\nredis_version:5.2.0\r\n");
        MetricPointRepository metricRepo = mock(MetricPointRepository.class);
        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        AlertService alertService = mock(AlertService.class);
        ConsoleProperties props = new ConsoleProperties();
        props.getMetrics().setIntervalMs(5000L);

        MonitorService monitorA = new MonitorService(
                instanceService, gateway, metricRepo, instRepo, alertService, leaderLease, props);
        MonitorService monitorB = new MonitorService(
                instanceService, gateway, metricRepo, instRepo, alertService, standbyLease, props);

        // 同一周期内两副本同时触发，仅主副本 A 采集 1 次，备副本 B 跳过
        monitorA.scheduledCollect();
        monitorB.scheduledCollect();
        verify(gateway, times(1)).info(any());

        // 下一周期（5 秒后）主副本 A 原地续租采集，备副本 B 仍受 10 秒租约保护无主备抖动
        currentInstant.set(currentInstant.get().plusSeconds(5));
        monitorA.scheduledCollect();
        monitorB.scheduledCollect();
        verify(gateway, times(2)).info(any());

        // 主副本宕机超过 10 秒租约过期后，备副本 B 自动接管采集
        currentInstant.set(currentInstant.get().plusSeconds(11));
        monitorB.scheduledCollect();
        verify(gateway, times(3)).info(any());

        // 备份清理同理：仅持锁主副本执行
        BackupRetentionService retentionA = new BackupRetentionService(
                backupRecordRepository, instanceRepository, props,
                mock(BackupStorageBackend.class), transactionManager, leaderLease);
        BackupRetentionService retentionB = new BackupRetentionService(
                backupRecordRepository, instanceRepository, props,
                mock(BackupStorageBackend.class), transactionManager, standbyLease);
        retentionA.scheduledPurge();
        BackupStorageBackend standbyStorage = mock(BackupStorageBackend.class);
        BackupRetentionService retentionBCheck = new BackupRetentionService(
                backupRecordRepository, instanceRepository, props,
                standbyStorage, transactionManager, standbyLease);
        retentionBCheck.scheduledPurge();
        verify(standbyStorage, never()).storageKey();
        assertThat(retentionB.view().enabled()).isTrue();
    }

    @Test
    void bootstrapInitializerToleratesConcurrentUniqueConstraintRace() throws Exception {
        UserRepository userRepo = mock(UserRepository.class);
        when(userRepo.count()).thenReturn(0L);
        when(userRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate admin"));

        DbInstanceRepository instRepo = mock(DbInstanceRepository.class);
        when(instRepo.findByName("local-6379")).thenReturn(java.util.Optional.empty());
        when(instRepo.save(any())).thenThrow(new DataIntegrityViolationException("duplicate instance"));

        PasswordEncoder encoder = mock(PasswordEncoder.class);
        when(encoder.encode(any())).thenReturn("{bcrypt}stub");

        ConsoleProperties props = new ConsoleProperties();
        props.getBootstrap().setInstance("local-6379@tiering-kv:6379");

        BootstrapDataInitializer initializer = new BootstrapDataInitializer(
                userRepo, instRepo, encoder, props);
        initializer.run(null);

        verify(userRepo, times(1)).save(any());
        verify(instRepo, times(1)).save(any());
    }
}
