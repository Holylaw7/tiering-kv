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
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * TD-100：SSTable 清单接口的鉴权、实例语义与失败状态。
 *
 * <p>失败必须明确（401 / 404 / 502），不得用空数组或零值掩盖。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class SystemTablesApiTest {

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
        TestUsers.ensure(userRepository, passwordEncoder, "viewer", "viewer123", Role.VIEWER);
        instanceId = instanceRepository.findByName("tables-offline")
                .map(DbInstance::getId)
                .orElseGet(() -> {
                    DbInstance instance = new DbInstance();
                    instance.setName("tables-offline");
                    instance.setHost("127.0.0.1");
                    instance.setPort(65001); // 无人监听：连接必然失败
                    instance.setEnabled(true);
                    return instanceRepository.save(instance).getId();
                });
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

    @Test
    void anonymousCannotReadInventory() throws Exception {
        mockMvc.perform(get("/api/v1/system/tables").param("instanceId", String.valueOf(instanceId)))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void viewerAndAdminCanReadInventory() throws Exception {
        for (String token : new String[] {login("admin", "admin123"), login("viewer", "viewer123")}) {
            // 该实例不可达：鉴权通过后应当得到明确的 502（连接失败码 50201），而不是空数组
            mockMvc.perform(get("/api/v1/system/tables")
                            .param("instanceId", String.valueOf(instanceId))
                            .header("Authorization", "Bearer " + token))
                    .andExpect(status().isBadGateway())
                    .andExpect(jsonPath("$.code").value(50201));
        }
    }

    @Test
    void unknownInstanceIsNotFound() throws Exception {
        String token = login("admin", "admin123");

        mockMvc.perform(get("/api/v1/system/tables")
                        .param("instanceId", "999999")
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value(40400));
    }

    @Test
    void offlineInstanceReportsExplicitUpstreamError() throws Exception {
        String token = login("admin", "admin123");

        String body = mockMvc.perform(get("/api/v1/system/tables")
                        .param("instanceId", String.valueOf(instanceId))
                        .header("Authorization", "Bearer " + token))
                .andExpect(status().isBadGateway())
                .andReturn().getResponse().getContentAsString();

        JsonNode node = objectMapper.readTree(body);
        // 连接失败与"读取后解析失败"是两个不同的错误码，前端据此区分展示
        assertThat(node.path("code").asInt()).isEqualTo(50201);
        assertThat(node.path("message").asText()).contains("无法连接实例");
    }
}