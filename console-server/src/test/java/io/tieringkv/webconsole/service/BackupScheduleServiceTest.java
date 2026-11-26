package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupSchedule;
import io.tieringkv.webconsole.domain.BackupScheduleMode;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.BackupScheduleRepository;
import io.tieringkv.webconsole.repository.ConsoleLeaseRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import java.time.Clock;
import java.time.Duration;
import java.time.LocalDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyInt;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v6.1.0 Stage A2：定时备份策略 CRUD、RBAC、Cron 解析与多副本租约锁调度测试。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupScheduleServiceTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private BackupScheduleRepository scheduleRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private ConsoleLeaseRepository leaseRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private InstanceService instanceService;

    @Autowired
    private AuditService auditService;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private String adminToken;
    private String viewerToken;
    private Long instanceId;

    @BeforeEach
    void setUp() throws Exception {
        scheduleRepository.deleteAll();
        leaseRepository.deleteAll();

        TestUsers.ensure(userRepository, passwordEncoder, "admin", "admin123", Role.ADMIN);
        TestUsers.ensure(userRepository, passwordEncoder, "viewer", "viewer123", Role.VIEWER);
        adminToken = login("admin", "admin123");
        viewerToken = login("viewer", "viewer123");

        DbInstance inst = instanceRepository.findByName("schedule-test-inst").orElseGet(() -> {
            DbInstance created = new DbInstance();
            created.setName("schedule-test-inst");
            created.setHost("127.0.0.1");
            created.setPort(16399);
            created.setEnabled(true);
            return instanceRepository.save(created);
        });
        instanceId = inst.getId();
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    void cronNormalizationSupportsUnix5AndSpring6AndRejectsInvalid() {
        assertThat(BackupScheduleService.normalizeCron("0 2 * * *")).isEqualTo("0 0 2 * * *");
        assertThat(BackupScheduleService.normalizeCron("*/15 * * * *")).isEqualTo("0 */15 * * * *");
        assertThat(BackupScheduleService.normalizeCron("0 30 3 * * MON")).isEqualTo("0 30 3 * * MON");
        assertThat(BackupScheduleService.parseCron("0 2 * * *")).isNotNull();

        assertThatThrownBy(() -> BackupScheduleService.parseCron("not-a-cron"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("5 段");
        assertThatThrownBy(() -> BackupScheduleService.parseCron("99 99 99 * * *"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("无效的 Cron");
    }

    @Test
    void crudLifecycleAndRbacEnforcedOverHttp() throws Exception {
        String createPayload = """
                {
                  "instanceId": %d,
                  "name": "nightly-logical",
                  "cronExpression": "0 2 * * *",
                  "mode": "LOGICAL",
                  "retentionCount": 5,
                  "enabled": true
                }
                """.formatted(instanceId);

        // VIEWER 创建被拒绝（403）
        mockMvc.perform(post("/api/v1/backups/schedules")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isForbidden());

        // ADMIN 创建成功，5 段 Unix Cron 自动归一化为 6 段并生成 nextRunAt
        String createdJson = mockMvc.perform(post("/api/v1/backups/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("nightly-logical"))
                .andExpect(jsonPath("$.data.cronExpression").value("0 0 2 * * *"))
                .andExpect(jsonPath("$.data.mode").value("LOGICAL"))
                .andExpect(jsonPath("$.data.retentionCount").value(5))
                .andExpect(jsonPath("$.data.nextRunAt").isNotEmpty())
                .andReturn().getResponse().getContentAsString();
        long scheduleId = objectMapper.readTree(createdJson).path("data").path("id").asLong();

        // 重名冲突返回 409
        mockMvc.perform(post("/api/v1/backups/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(createPayload))
                .andExpect(status().isConflict());

        // VIEWER 可只读查看列表
        mockMvc.perform(get("/api/v1/backups/schedules")
                        .param("instanceId", String.valueOf(instanceId))
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data[0].name").value("nightly-logical"))
                .andExpect(jsonPath("$.data[0].instanceName").value("schedule-test-inst"));

        // 更新为 CHECKPOINT 且停用
        String updatePayload = """
                {
                  "instanceId": %d,
                  "name": "nightly-checkpoint",
                  "cronExpression": "0 0 3 * * *",
                  "mode": "CHECKPOINT",
                  "retentionCount": 14,
                  "enabled": false
                }
                """.formatted(instanceId);
        mockMvc.perform(put("/api/v1/backups/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(updatePayload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.name").value("nightly-checkpoint"))
                .andExpect(jsonPath("$.data.mode").value("CHECKPOINT"))
                .andExpect(jsonPath("$.data.enabled").value(false))
                .andExpect(jsonPath("$.data.nextRunAt").doesNotExist());

        // 非法 Cron 返回 400
        mockMvc.perform(post("/api/v1/backups/schedules")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("""
                                {"instanceId":%d,"name":"bad-cron","cronExpression":"* * *","mode":"LOGICAL"}
                                """.formatted(instanceId)))
                .andExpect(status().isBadRequest());

        // VIEWER 删除被拒，ADMIN 删除成功
        mockMvc.perform(delete("/api/v1/backups/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(delete("/api/v1/backups/schedules/" + scheduleId)
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());

        JsonNode listAfter = objectMapper.readTree(mockMvc.perform(get("/api/v1/backups/schedules")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString());
        assertThat(listAfter.path("data").size()).isZero();
    }

    @Test
    void dueSchedulerHonorsHaLeaseLockAndExecutesLogicalAndCheckpointWithRetention() {
        BackupService mockBackup = mock(BackupService.class);
        BackupRetentionService mockRetention = mock(BackupRetentionService.class);

        BackupRecord fakeRecord = new BackupRecord();
        fakeRecord.setId(77L);
        fakeRecord.setInstanceId(instanceId);
        fakeRecord.setFileName("backup-test.jsonl.gz");
        fakeRecord.setStatus(BackupStatus.RUNNING);
        when(mockBackup.export(instanceId)).thenReturn(fakeRecord);
        when(mockRetention.purgeInstance(instanceId, 3)).thenReturn(2);
        when(mockBackup.triggerCheckpoint(instanceId))
                .thenReturn(new BackupService.CheckpointResult(true, "Background saving started", 1700000000L));

        ConsoleProperties props = new ConsoleProperties();
        props.getBackup().getSchedule().setEnabled(true);

        DbLeaseLockService replicaA = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-A", Clock.systemDefaultZone());
        DbLeaseLockService replicaB = new DbLeaseLockService(
                leaseRepository, transactionManager, "replica-B", Clock.systemDefaultZone());

        BackupScheduleService serviceOnA = new BackupScheduleService(
                scheduleRepository, instanceRepository, instanceService,
                mockBackup, mockRetention, auditService, props, replicaA);
        BackupScheduleService serviceOnB = new BackupScheduleService(
                scheduleRepository, instanceRepository, instanceService,
                mockBackup, mockRetention, auditService, props, replicaB);

        LocalDateTime past = LocalDateTime.now().minusMinutes(2);

        BackupSchedule logical = new BackupSchedule();
        logical.setInstanceId(instanceId);
        logical.setName("due-logical");
        logical.setCronExpression("0 0 * * * *");
        logical.setMode(BackupScheduleMode.LOGICAL);
        logical.setRetentionCount(3);
        logical.setEnabled(true);
        logical.setNextRunAt(past);
        scheduleRepository.save(logical);

        BackupSchedule checkpoint = new BackupSchedule();
        checkpoint.setInstanceId(instanceId);
        checkpoint.setName("due-checkpoint");
        checkpoint.setCronExpression("0 0 * * * *");
        checkpoint.setMode(BackupScheduleMode.CHECKPOINT);
        checkpoint.setRetentionCount(7);
        checkpoint.setEnabled(true);
        checkpoint.setNextRunAt(past.plusSeconds(10));
        scheduleRepository.save(checkpoint);

        // Replica B 先抢到租约 → Replica A 调用 scheduledTick() 应跳过，零重复执行
        assertThat(replicaB.tryAcquire(BackupScheduleService.BACKUP_SCHEDULE_LEASE_KEY, Duration.ofMinutes(1)))
                .isTrue();
        serviceOnA.scheduledTick();
        verify(mockBackup, never()).export(anyLong());
        verify(mockBackup, never()).triggerCheckpoint(anyLong());

        // Replica B 持有租约执行 scheduledTick() → 两个到期策略各执行一次并推进 nextRunAt
        serviceOnB.scheduledTick();
        verify(mockBackup).export(instanceId);
        verify(mockRetention).purgeInstance(instanceId, 3);
        verify(mockBackup).triggerCheckpoint(instanceId);

        BackupSchedule updatedLogical = scheduleRepository.findById(logical.getId()).orElseThrow();
        assertThat(updatedLogical.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(updatedLogical.getLastMessage()).contains("#77").contains("清理旧备份 2 份");
        assertThat(updatedLogical.getNextRunAt()).isAfter(LocalDateTime.now());

        BackupSchedule updatedCheckpoint = scheduleRepository.findById(checkpoint.getId()).orElseThrow();
        assertThat(updatedCheckpoint.getLastStatus()).isEqualTo("SUCCESS");
        assertThat(updatedCheckpoint.getNextRunAt()).isAfter(LocalDateTime.now());

        // runNow 手动执行与异常状态记录
        when(mockBackup.export(instanceId)).thenThrow(BusinessException.conflict("已有备份在执行"));
        assertThatThrownBy(() -> serviceOnB.runNow(logical.getId()))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("已有备份");
        BackupSchedule failedLogical = scheduleRepository.findById(logical.getId()).orElseThrow();
        assertThat(failedLogical.getLastStatus()).isEqualTo("FAILED");
        assertThat(failedLogical.getLastMessage()).contains("已有备份");
        verify(mockRetention, org.mockito.Mockito.times(1)).purgeInstance(anyLong(), anyInt());
    }
}
