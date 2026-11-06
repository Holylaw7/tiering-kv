package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.support.TestUsers;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-102：失败操作必须留下审计记录（不被业务事务回滚）。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditFailureTrailTest {

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

    private Long instanceId;

    @BeforeEach
    void setUp() {
        TestUsers.ensure(userRepository, passwordEncoder, "admin", "admin123", Role.ADMIN);
        instanceId = instanceRepository.findByName("audit-trail")
                .map(DbInstance::getId)
                .orElseGet(() -> {
                    DbInstance instance = new DbInstance();
                    instance.setName("audit-trail");
                    instance.setHost("127.0.0.1");
                    instance.setPort(65002);
                    instance.setEnabled(true);
                    return instanceRepository.save(instance).getId();
                });
    }

    private String login() throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"admin\",\"password\":\"admin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        JsonNode node = objectMapper.readTree(body);
        return node.path("data").path("token").asText();
    }

    @Test
    void blockedTerminalCommandIsAuditedEvenThoughItFails() throws Exception {
        String token = login();

        // 被策略拦截 → 400（业务异常）
        mockMvc.perform(post("/api/v1/terminal/execute")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":" + instanceId + ",\"command\":\"FLUSHALL\"}"))
                .andExpect(status().isBadRequest());

        String audit = mockMvc.perform(get("/api/v1/audit")
                        .param("action", "TERMINAL_BLOCKED")
                        .param("size", "5")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();

        JsonNode data = objectMapper.readTree(audit).path("data");
        assertThat(data.path("total").asLong()).isGreaterThan(0);
        assertThat(data.path("items").get(0).path("success").asBoolean()).isFalse();
    }
}
