package io.tieringkv.webconsole.web;

import tools.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.service.BackupStorageBackend;
import io.tieringkv.webconsole.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O1-A / O1-B：备份下载权限收敛（ADMIN/OPS）与异步恢复接口契约校验（复用共享测试上下文，避免污染 H2）。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupControllerSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private BackupRecordRepository backupRecordRepository;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private BackupStorageBackend storageBackend;

    private String viewerToken;
    private String opsToken;
    private String adminToken;
    private Long instanceId;

    @BeforeEach
    void setUp() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "o1_viewer", "viewer123", Role.VIEWER);
        TestUsers.ensure(userRepository, passwordEncoder, "o1_ops", "ops123456", Role.OPS);
        TestUsers.ensure(userRepository, passwordEncoder, "o1_admin", "admin1234", Role.ADMIN);
        viewerToken = login("o1_viewer", "viewer123");
        opsToken = login("o1_ops", "ops123456");
        adminToken = login("o1_admin", "admin1234");
        instanceId = instanceRepository.findByName("o1-sec-inst")
                .map(DbInstance::getId)
                .orElseGet(() -> {
                    DbInstance inst = new DbInstance();
                    inst.setName("o1-sec-inst");
                    inst.setHost("127.0.0.1");
                    inst.setPort(65099);
                    inst.setEnabled(true);
                    return instanceRepository.save(inst).getId();
                });
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
    void downloadAllowedForOpsAndAdminButForbiddenForViewer() throws Exception {
        String fileName = "o1-sec-" + System.nanoTime() + ".jsonl";
        try (OutputStream out = storageBackend.openTempOutput(fileName)) {
            out.write("{\"key\":\"k1\",\"type\":\"string\",\"ttl\":-1,\"value\":\"v1\"}\n"
                    .getBytes(StandardCharsets.UTF_8));
        }
        storageBackend.publish(fileName);

        BackupRecord rec = new BackupRecord();
        rec.setInstanceId(instanceId);
        rec.setFileName(fileName);
        rec.setSizeBytes(56L);
        rec.setKeyCount(1L);
        rec.setProcessedKeys(1L);
        rec.setStatus(BackupStatus.SUCCESS);
        rec.setRestoreStatus(RestoreStatus.NONE);
        rec.setCreatedAt(LocalDateTime.now());
        rec.setFinishedAt(LocalDateTime.now());
        rec = backupRecordRepository.save(rec);

        try {
            mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isForbidden())
                    .andExpect(jsonPath("$.code").value(40300));

            mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + opsToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"" + fileName + "\""));

            mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk());
        } finally {
            storageBackend.deleteIfExists(fileName);
            backupRecordRepository.deleteById(rec.getId());
        }
    }

    @Test
    void restoreRequiresAdminAndReturnsAsyncRecord() throws Exception {
        String fileName = "o1-restore-" + System.nanoTime() + ".jsonl";
        try (OutputStream out = storageBackend.openTempOutput(fileName)) {
            out.write("".getBytes(StandardCharsets.UTF_8));
        }
        storageBackend.publish(fileName);

        BackupRecord rec = new BackupRecord();
        rec.setInstanceId(instanceId);
        rec.setFileName(fileName);
        rec.setSizeBytes(0L);
        rec.setKeyCount(0L);
        rec.setProcessedKeys(0L);
        rec.setStatus(BackupStatus.SUCCESS);
        rec.setRestoreStatus(RestoreStatus.NONE);
        rec.setCreatedAt(LocalDateTime.now());
        rec.setFinishedAt(LocalDateTime.now());
        rec = backupRecordRepository.save(rec);

        try {
            mockMvc.perform(post("/api/v1/backups/" + rec.getId() + "/restore")
                            .header("Authorization", "Bearer " + opsToken))
                    .andExpect(status().isForbidden());

            mockMvc.perform(post("/api/v1/backups/" + rec.getId() + "/restore")
                            .header("Authorization", "Bearer " + adminToken))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.code").value(0))
                    .andExpect(jsonPath("$.data.id").value(rec.getId()))
                    .andExpect(jsonPath("$.data.processedKeys").value(0));

            // 等待异步空文件回放任务落库收敛，避免与 finally 清理产生竞态
            for (int i = 0; i < 50; i++) {
                BackupRecord latest = backupRecordRepository.findById(rec.getId()).orElse(null);
                if (latest == null || latest.getRestoreStatus() != RestoreStatus.RESTORING) {
                    break;
                }
                Thread.sleep(20L);
            }
        } finally {
            storageBackend.deleteIfExists(fileName);
            backupRecordRepository.deleteById(rec.getId());
        }
    }
}
