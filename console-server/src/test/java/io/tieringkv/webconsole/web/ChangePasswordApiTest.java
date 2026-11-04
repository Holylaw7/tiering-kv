package io.tieringkv.webconsole.web;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.tieringkv.webconsole.domain.Role;
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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** TD-087：任意已登录用户可自助修改口令。 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class ChangePasswordApiTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String token;

    @BeforeEach
    void setUp() throws Exception {
        var user = TestUsers.ensure(userRepository, passwordEncoder,
                "selfpwd", "origin123", Role.OPS);
        user.setPassword(passwordEncoder.encode("origin123"));
        userRepository.save(user);

        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"selfpwd\",\"password\":\"origin123\"}"))
                .andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString();
        token = objectMapper.readTree(body).path("data").path("token").asText();
    }

    private void change(String oldPassword, String newPassword, int expectedStatus)
            throws Exception {
        mockMvc.perform(post("/api/v1/auth/password")
                        .header("Authorization", "Bearer " + token)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"" + oldPassword
                                + "\",\"newPassword\":\"" + newPassword + "\"}"))
                .andExpect(status().is(expectedStatus));
    }

    @Test
    void changesOwnPasswordAndOldOneStopsWorking() throws Exception {
        change("origin123", "newpass123", 200);

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"selfpwd\",\"password\":\"origin123\"}"))
                .andExpect(status().isUnauthorized());

        mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"selfpwd\",\"password\":\"newpass123\"}"))
                .andExpect(status().isOk());
    }

    @Test
    void rejectsWrongOldPassword() throws Exception {
        change("wrong-old", "newpass123", 400);
    }

    @Test
    void rejectsSamePassword() throws Exception {
        change("origin123", "origin123", 400);
    }

    @Test
    void rejectsTooShortPassword() throws Exception {
        change("origin123", "123", 400);
    }

    @Test
    void anonymousCannotChangePassword() throws Exception {
        mockMvc.perform(post("/api/v1/auth/password")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"oldPassword\":\"origin123\",\"newPassword\":\"newpass123\"}"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value(40100));
    }
}
