package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.BackupRecord;
import io.tieringkv.webconsole.domain.BackupStatus;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.RestoreStatus;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.BackupRecordRepository;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.service.BackupStorageBackend;
import io.tieringkv.webconsole.support.TestUsers;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** N1-A 流式下载与 N1-B BGSAVE/LASTSAVE 控制器 API、权限与降级测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class BackupCheckpointApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    @Autowired
    private DbInstanceRepository instanceRepository;

    @Autowired
    private BackupRecordRepository backupRecordRepository;

    @Autowired
    private BackupStorageBackend storageBackend;

    @Autowired
    private OpLogRepository opLogRepository;

    @Test
    void streamsBackupDownloadAndRejectsMissingFile() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "cpviewer", "viewer123", Role.VIEWER);
        TestUsers.ensure(userRepository, passwordEncoder, "cpdlops", "ops12345", Role.OPS);
        String viewerToken = login("cpviewer", "viewer123");
        String opsToken = login("cpdlops", "ops12345");

        String fileName = "stream-test-" + System.nanoTime() + ".jsonl.gz";
        byte[] bytes = "stream-payload-n1".getBytes(StandardCharsets.UTF_8);
        try (OutputStream out = storageBackend.openTempOutput(fileName)) {
            out.write(bytes);
        }
        storageBackend.publish(fileName);

        BackupRecord rec = new BackupRecord();
        rec.setInstanceId(1L);
        rec.setFileName(fileName);
        rec.setSizeBytes(bytes.length);
        rec.setKeyCount(1L);
        rec.setStatus(BackupStatus.SUCCESS);
        rec.setRestoreStatus(RestoreStatus.NONE);
        rec.setCreatedAt(LocalDateTime.now());
        rec.setFinishedAt(LocalDateTime.now());
        rec = backupRecordRepository.save(rec);

        try {
            // O1-A：VIEWER 无权下载整库明文快照
            mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isForbidden());

            byte[] downloaded = mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + opsToken))
                    .andExpect(status().isOk())
                    .andExpect(header().string("Content-Disposition",
                            "attachment; filename=\"" + fileName + "\""))
                    .andReturn().getResponse().getContentAsByteArray();
            assertThat(downloaded).isEqualTo(bytes);

            storageBackend.deleteIfExists(fileName);
            mockMvc.perform(get("/api/v1/backups/" + rec.getId() + "/download")
                            .header("Authorization", "Bearer " + opsToken))
                    .andExpect(status().isNotFound());
        } finally {
            storageBackend.deleteIfExists(fileName);
            backupRecordRepository.deleteById(rec.getId());
        }
    }

    @Test
    void checkpointTriggerRbacStatusAndLegacyFallback() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "cpops", "ops12345", Role.OPS);
        TestUsers.ensure(userRepository, passwordEncoder, "cpview", "view1234", Role.VIEWER);
        String opsToken = login("cpops", "ops12345");
        String viewerToken = login("cpview", "view1234");

        AtomicBoolean legacyMode = new AtomicBoolean(false);
        AtomicBoolean alreadyRunning = new AtomicBoolean(false);
        AtomicLong lastSaveTs = new AtomicLong(1700000100L);

        try (StubRespServer stub = new StubRespServer(args -> {
            String cmd = args.get(0).toUpperCase(Locale.ROOT);
            if ("PING".equals(cmd)) {
                return "+PONG\r\n";
            }
            if (legacyMode.get() && ("BGSAVE".equals(cmd) || "LASTSAVE".equals(cmd))) {
                return "-ERR unknown command '" + cmd.toLowerCase(Locale.ROOT) + "'\r\n";
            }
            return switch (cmd) {
                case "BGSAVE" -> {
                    if (alreadyRunning.get()) {
                        yield "-ERR Background save already in progress\r\n";
                    }
                    lastSaveTs.set(1700000200L);
                    yield "+Background saving started\r\n";
                }
                case "LASTSAVE" -> ":" + lastSaveTs.get() + "\r\n";
                case "INFO" -> {
                    String payload = "# Persistence\r\n"
                            + "loading:0\r\n"
                            + "rdb_bgsave_in_progress:" + (alreadyRunning.get() ? 1 : 0) + "\r\n"
                            + "rdb_last_save_time:" + lastSaveTs.get() + "\r\n"
                            + "rdb_last_bgsave_status:ok\r\n"
                            + "rdb_last_bgsave_time_sec:1\r\n"
                            + "rdb_last_bgsave_duration_ms:25\r\n";
                    yield "$" + payload.length() + "\r\n" + payload + "\r\n";
                }
                default -> "+OK\r\n";
            };
        })) {
            DbInstance inst = new DbInstance();
            inst.setName("cp-stub-" + System.nanoTime());
            inst.setHost("127.0.0.1");
            inst.setPort(stub.port());
            inst.setEnabled(true);
            inst = instanceRepository.save(inst);

            try {
                // VIEWER 不允许触发物理快照
                mockMvc.perform(post("/api/v1/backups/checkpoint")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isForbidden());

                // VIEWER 可只读查询状态
                mockMvc.perform(get("/api/v1/backups/checkpoint/status")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.supported").value(true))
                        .andExpect(jsonPath("$.data.inProgress").value(false))
                        .andExpect(jsonPath("$.data.lastSaveTime").value(1700000100L));

                // OPS 成功触发 BGSAVE
                mockMvc.perform(post("/api/v1/backups/checkpoint")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + opsToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.triggered").value(true))
                        .andExpect(jsonPath("$.data.message").value("Background saving started"))
                        .andExpect(jsonPath("$.data.lastSaveTime").value(1700000200L));

                assertThat(opLogRepository.findAll()).anyMatch(l ->
                        "BACKUP_CHECKPOINT".equals(l.getAction()) && l.isSuccess());

                // 进行中重复触发返回 triggered=false 而非 500
                alreadyRunning.set(true);
                mockMvc.perform(post("/api/v1/backups/checkpoint")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + opsToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.triggered").value(false));

                // 旧实例不支持 LASTSAVE 时状态接口优雅降级 supported=false
                legacyMode.set(true);
                mockMvc.perform(get("/api/v1/backups/checkpoint/status")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.supported").value(false))
                        .andExpect(jsonPath("$.data.lastStatus").value("unsupported"));
            } finally {
                instanceRepository.deleteById(inst.getId());
            }
        }
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.path("data").path("token").asText();
    }

    @FunctionalInterface
    interface RespHandler {
        String handle(List<String> commandArgs);
    }

    static final class StubRespServer implements AutoCloseable {
        private final ServerSocket server;
        private final Thread worker;

        StubRespServer(RespHandler handler) throws IOException {
            this.server = new ServerSocket(0);
            this.worker = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try {
                        Socket client = server.accept();
                        Thread.startVirtualThread(() -> serve(client, handler));
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });
        }

        int port() {
            return server.getLocalPort();
        }

        private static void serve(Socket socket, RespHandler handler) {
            try (socket;
                 BufferedReader reader = new BufferedReader(
                         new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                 OutputStream out = socket.getOutputStream()) {
                String line;
                while ((line = reader.readLine()) != null) {
                    if (!line.startsWith("*")) {
                        continue;
                    }
                    int count = Integer.parseInt(line.substring(1).trim());
                    List<String> args = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        String lenLine = reader.readLine();
                        if (lenLine == null || !lenLine.startsWith("$")) {
                            return;
                        }
                        int len = Integer.parseInt(lenLine.substring(1).trim());
                        char[] buf = new char[len];
                        int read = 0;
                        while (read < len) {
                            int n = reader.read(buf, read, len - read);
                            if (n < 0) {
                                return;
                            }
                            read += n;
                        }
                        reader.readLine(); // CRLF
                        args.add(new String(buf));
                    }
                    String resp = handler.handle(args);
                    out.write(resp.getBytes(StandardCharsets.UTF_8));
                    out.flush();
                }
            } catch (IOException ignored) {
            }
        }

        @Override
        public void close() throws IOException {
            server.close();
            worker.interrupt();
        }
    }
}
