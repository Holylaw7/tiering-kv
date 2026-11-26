package io.tieringkv.webconsole.web;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.UserRepository;
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

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** O3-A / O3-B：Actuator 端点权限收敛与告警 Webhook 渠道 RBAC / 脱敏校验。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SecurityEndpointsTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String viewerToken;
    private String opsToken;
    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "o3_viewer", "viewer123", Role.VIEWER);
        TestUsers.ensure(userRepository, passwordEncoder, "o3_ops", "ops123456", Role.OPS);
        TestUsers.ensure(userRepository, passwordEncoder, "o3_admin", "admin1234", Role.ADMIN);
        viewerToken = login("o3_viewer", "viewer123");
        opsToken = login("o3_ops", "ops123456");
        adminToken = login("o3_admin", "admin1234");
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
    void actuatorHealthAnonymousButInfoRequiresAdmin() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/actuator/info"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/actuator/info")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk());
    }

    @Test
    void alertChannelRbacAndSecretMasking() throws Exception {
        String payload = """
                {
                  "name": "o3-webhook-test",
                  "type": "WEBHOOK",
                  "url": "https://hooks.example.com/alerts",
                  "secret": "super-secret-hmac-key",
                  "silenceSeconds": 120,
                  "enabled": true
                }
                """;

        // VIEWER 不允许创建
        mockMvc.perform(post("/api/v1/alerts/channels")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isForbidden());

        // OPS 创建成功，返回体脱敏不回传明文 secret
        String createdBody = mockMvc.perform(post("/api/v1/alerts/channels")
                        .header("Authorization", "Bearer " + opsToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(payload))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0))
                .andExpect(jsonPath("$.data.hasSecret").value(true))
                .andExpect(jsonPath("$.data.silenceSeconds").value(120))
                .andReturn().getResponse().getContentAsString();
        assertThat(createdBody).doesNotContain("super-secret-hmac-key");

        JsonNode data = objectMapper.readTree(createdBody).path("data");
        long id = data.path("id").asLong();
        try {
            // VIEWER 可读脱敏列表但不可删除或触发测试
            String listBody = mockMvc.perform(get("/api/v1/alerts/channels")
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isOk())
                    .andReturn().getResponse().getContentAsString();
            assertThat(listBody).contains("o3-webhook-test").doesNotContain("super-secret-hmac-key");

            mockMvc.perform(delete("/api/v1/alerts/channels/" + id)
                            .header("Authorization", "Bearer " + viewerToken))
                    .andExpect(status().isForbidden());
        } finally {
            mockMvc.perform(delete("/api/v1/alerts/channels/" + id)
                            .header("Authorization", "Bearer " + opsToken))
                    .andExpect(status().isOk());
        }
    }
}
