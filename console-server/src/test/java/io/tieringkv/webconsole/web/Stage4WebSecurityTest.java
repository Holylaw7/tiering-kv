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

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
class Stage4WebSecurityTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private UserRepository userRepository;

    @Autowired
    private PasswordEncoder passwordEncoder;

    private String viewerToken;

    @BeforeEach
    void setUp() throws Exception {
        TestUsers.ensure(userRepository, passwordEncoder, "viewer", "viewer123", Role.VIEWER);
        String body = mockMvc.perform(post("/api/v1/auth/login")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"username\":\"viewer\",\"password\":\"viewer123\"}"))
                .andReturn().getResponse().getContentAsString();
        viewerToken = objectMapper.readTree(body).path("data").path("token").asText();
    }

    @Test
    void viewerForbiddenFromExecutingTerminalCommand() throws Exception {
        mockMvc.perform(post("/api/v1/terminal/execute")
                        .header("Authorization", "Bearer " + viewerToken)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"instanceId\":1,\"command\":\"SET k v\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void alertEventsStatusFilterHandlesZeroPageSafely() throws Exception {
        mockMvc.perform(get("/api/v1/alerts/events")
                        .param("status", "FIRING")
                        .param("page", "0")
                        .param("size", "10")
                        .header("Authorization", "Bearer " + viewerToken))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code").value(0));
    }
}
