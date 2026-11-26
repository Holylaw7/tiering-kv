package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
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
import tools.jackson.databind.ObjectMapper;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * v6.1.0 Stage A2：JWT token_version 实时吊销闭环测试。
 * 覆盖登出、自助改密、管理员重置密码、禁用账号与角色变更后的即时失效。
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class TokenRevocationApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String adminToken;

    @BeforeEach
    void setUp() throws Exception {
        User admin = TestUsers.ensure(userRepository, passwordEncoder, "admin", "admin123", Role.ADMIN);
        admin.setStatus(1);
        admin.setPassword(passwordEncoder.encode("admin123"));
        userRepository.save(admin);
        adminToken = login("admin", "admin123");
    }

    private User ensureActiveUser(String username, String password, Role role) {
        User user = TestUsers.ensure(userRepository, passwordEncoder, username, password, role);
        user.setRole(role);
        user.setStatus(1);
        user.setPassword(passwordEncoder.encode(password));
        return userRepository.save(user);
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
    void logoutImmediatelyRevokesTokenAndAllowsFreshLogin() throws Exception {
        ensureActiveUser("revoke_logout", "pass1234", Role.OPS);
        String token = login("revoke_logout", "pass1234");

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/auth/logout").header("Authorization", "Bearer " + token))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));

        // 旧令牌立即返回 401
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));

        // 重新登录签发新版本令牌恢复可用
        String freshToken = login("revoke_logout", "pass1234");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + freshToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.data.username").value("revoke_logout"));
    }

    @Test
    void changePasswordImmediatelyRevokesOldToken() throws Exception {
        ensureActiveUser("revoke_pwd", "oldpass12", Role.OPS);
        String oldToken = login("revoke_pwd", "oldpass12");

        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + oldToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"oldpass12\",\"newPassword\":\"newpass34\"}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + oldToken))
                .andExpect(status().isUnauthorized());

        String newToken = login("revoke_pwd", "newpass34");
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + newToken))
                .andExpect(status().isOk());
    }

    @Test
    void adminResetPasswordAndDisableAndRoleChangeRevokeTargetToken() throws Exception {
        User target = ensureActiveUser("revoke_target", "target123", Role.OPS);

        // 1. 管理员重置密码 → 目标旧令牌立即失效
        String token1 = login("revoke_target", "target123");
        mockMvc.perform(put("/api/v1/users/" + target.getId() + "/password")
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"password\":\"reset456\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token1))
                .andExpect(status().isUnauthorized());

        // 2. 管理员降级角色（OPS -> VIEWER）→ 目标旧令牌立即失效
        String token2 = login("revoke_target", "reset456");
        mockMvc.perform(put("/api/v1/users/" + target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"role\":\"VIEWER\"}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token2))
                .andExpect(status().isUnauthorized());

        // 3. 管理员禁用账号（status=0）→ 目标旧令牌立即失效
        String token3 = login("revoke_target", "reset456");
        mockMvc.perform(put("/api/v1/users/" + target.getId())
                        .header("Authorization", "Bearer " + adminToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"status\":0}"))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/auth/me").header("Authorization", "Bearer " + token3))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void anonymousLogoutIsRejected() throws Exception {
        mockMvc.perform(post("/api/v1/auth/logout"))
                .andExpect(status().isUnauthorized());
    }
}
