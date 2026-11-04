package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.OpLog;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.repository.OpLogRepository;
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
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TD-090：审计筛选与 CSV 导出。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class AuditExportTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private OpLogRepository opLogRepository;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;
    private String opsToken;

    @BeforeEach
    void setUp() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "auditadmin", "admin123", Role.ADMIN);
        TestUsers.ensure(userRepository, passwordEncoder, "auditops", "ops12345", Role.OPS);
        adminToken = login("auditadmin", "admin123");
        opsToken = login("auditops", "ops12345");

        opLogRepository.deleteAll();
        opLogRepository.save(entry("alice", "KEY_SET", true));
        opLogRepository.save(entry("bob", "KEY_DELETE", false));
    }

    private String login(String username, String password) throws Exception {
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"" + username + "\",\"password\":\"" + password + "\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        return objectMapper.readTree(body).path("data").path("token").asText();
    }

    private static OpLog entry(String username, String action, boolean success) {
        OpLog log = new OpLog();
        log.setUsername(username);
        log.setAction(action);
        log.setTarget("local/audit-key");
        log.setDetail("detail-" + action);
        log.setIp("127.0.0.1");
        log.setSuccess(success);
        return log;
    }

    @Test
    void filtersByUsername() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("username", "alice")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].username").value("alice"));
    }

    @Test
    void filtersByResult() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("success", "false")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1))
                .andExpect(jsonPath("$.data.items[0].action").value("KEY_DELETE"));
    }

    @Test
    void filtersByActionFragment() throws Exception {
        mockMvc.perform(get("/api/v1/audit").param("action", "delete")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.total").value(1));
    }

    @Test
    void exportsCsvWithHeaderAndRows() throws Exception {
        byte[] body = mockMvc.perform(get("/api/v1/audit/export")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Disposition", containsString("audit-logs.csv")))
                .andExpect(content().contentTypeCompatibleWith(MediaType.parseMediaType("text/csv")))
                .andReturn().getResponse().getContentAsByteArray();

        String csv = new String(body, java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFF");
        assertThat(csv).contains("时间,用户,动作,对象,详情,IP,结果");
        assertThat(csv).contains("alice").contains("KEY_SET").contains("成功");
        assertThat(csv).contains("bob").contains("KEY_DELETE").contains("失败");
    }

    @Test
    void exportHonoursFilters() throws Exception {
        String csv = mockMvc.perform(get("/api/v1/audit/export").param("username", "bob")
                        .header("Authorization", "Bearer " + adminToken))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv).contains("bob");
        assertThat(csv).doesNotContain("alice");
        org.hamcrest.MatcherAssert.assertThat(csv, not(containsString("KEY_SET")));
    }

    @Test
    void nonAdminCannotReadOrExport() throws Exception {
        mockMvc.perform(get("/api/v1/audit").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/audit/export").header("Authorization", "Bearer " + opsToken))
                .andExpect(status().isForbidden());
    }
}
