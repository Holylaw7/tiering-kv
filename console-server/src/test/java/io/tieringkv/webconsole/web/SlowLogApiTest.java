package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.OpLogRepository;
import io.tieringkv.webconsole.repository.UserRepository;
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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** N2-A：慢查询列表/重置 API、RBAC 权限、边界参数与旧版本降级测试。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SlowLogApiTest {

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
    private OpLogRepository opLogRepository;

    @Test
    void listsSlowLogResetsWithAuditBlocksViewerAndHandlesLegacyFallback() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "slops", "ops12345", Role.OPS);
        TestUsers.ensure(userRepository, passwordEncoder, "slview", "view1234", Role.VIEWER);
        String opsToken = login("slops", "ops12345");
        String viewerToken = login("slview", "view1234");

        AtomicBoolean legacyMode = new AtomicBoolean(false);
        AtomicInteger resetCount = new AtomicInteger(0);

        try (StubRespServer stub = new StubRespServer(args -> {
            String cmd = args.get(0).toUpperCase(Locale.ROOT);
            if ("PING".equals(cmd)) {
                return "+PONG\r\n";
            }
            if (legacyMode.get() && "SLOWLOG".equals(cmd)) {
                return "-ERR unknown command 'slowlog'\r\n";
            }
            if ("SLOWLOG".equals(cmd) && args.size() >= 2) {
                String sub = args.get(1).toUpperCase(Locale.ROOT);
                return switch (sub) {
                    case "LEN" -> ":" + (resetCount.get() > 0 ? 0 : 2) + "\r\n";
                    case "GET" -> resetCount.get() > 0
                            ? "*0\r\n"
                            : "*2\r\n"
                              + "*4\r\n:12\r\n:1795420001\r\n:15200\r\n*3\r\n$3\r\nset\r\n$5\r\nhot:1\r\n$3\r\nval\r\n"
                              + "*4\r\n:11\r\n:1795420000\r\n:8400\r\n*2\r\n$4\r\nauth\r\n$10\r\n<redacted>\r\n";
                    case "RESET" -> {
                        resetCount.incrementAndGet();
                        yield "+OK\r\n";
                    }
                    default -> "-ERR Unknown SLOWLOG subcommand\r\n";
                };
            }
            return "+OK\r\n";
        })) {
            DbInstance inst = new DbInstance();
            inst.setName("sl-stub-" + System.nanoTime());
            inst.setHost("127.0.0.1");
            inst.setPort(stub.port());
            inst.setEnabled(true);
            inst = instanceRepository.save(inst);

            try {
                // VIEWER 只读列表成功
                mockMvc.perform(get("/api/v1/slowlog")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .param("limit", "20")
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.supported").value(true))
                        .andExpect(jsonPath("$.data.totalLen").value(2))
                        .andExpect(jsonPath("$.data.entries.length()").value(2))
                        .andExpect(jsonPath("$.data.entries[0].id").value(12))
                        .andExpect(jsonPath("$.data.entries[0].durationMicros").value(15200))
                        .andExpect(jsonPath("$.data.entries[0].command").value("SET"))
                        .andExpect(jsonPath("$.data.entries[1].args[1]").value("<redacted>"));

                // 非法 limit 返回 400
                mockMvc.perform(get("/api/v1/slowlog")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .param("limit", "300")
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isBadRequest());

                // VIEWER 清空返回 403
                mockMvc.perform(post("/api/v1/slowlog/reset")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isForbidden());

                // OPS 清空成功并留痕 SLOWLOG_RESET
                mockMvc.perform(post("/api/v1/slowlog/reset")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + opsToken))
                        .andExpect(status().isOk());

                assertThat(resetCount.get()).isEqualTo(1);
                assertThat(opLogRepository.findAll()).anyMatch(l ->
                        "SLOWLOG_RESET".equals(l.getAction()) && l.isSuccess());

                // 清空后查询返回 0 条
                mockMvc.perform(get("/api/v1/slowlog")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.totalLen").value(0))
                        .andExpect(jsonPath("$.data.entries.length()").value(0));

                // 旧版本不支持 SLOWLOG 时优雅降级为 supported=false 而非 500
                legacyMode.set(true);
                mockMvc.perform(get("/api/v1/slowlog")
                                .param("instanceId", String.valueOf(inst.getId()))
                                .header("Authorization", "Bearer " + viewerToken))
                        .andExpect(status().isOk())
                        .andExpect(jsonPath("$.data.supported").value(false));
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
                        reader.readLine();
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
