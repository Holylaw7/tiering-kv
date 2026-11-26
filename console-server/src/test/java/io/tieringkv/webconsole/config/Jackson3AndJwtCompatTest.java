package io.tieringkv.webconsole.config;

import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.security.JwtService;
import io.tieringkv.webconsole.security.UserPrincipal;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import tools.jackson.databind.ObjectMapper;

import java.time.LocalDateTime;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;

/** B1 回归护栏：验证 Spring Boot 4 / Jackson 3 日期格式、non_null 策略与无 jjwt-jackson JWT 编解码。 */
@SpringBootTest
@ActiveProfiles("test")
class Jackson3AndJwtCompatTest {

    @Autowired
    private ObjectMapper objectMapper;

    @Autowired
    private JwtService jwtService;

    record SamplePayload(LocalDateTime createdAt, String optionalNote) {
    }

    @Test
    void localDateTimeUsesStandardPatternAndNullsAreOmitted() {
        LocalDateTime ts = LocalDateTime.of(2026, 11, 26, 9, 45, 30);
        String json = objectMapper.writeValueAsString(new SamplePayload(ts, null));

        assertThat(json).contains("\"2026-11-26 09:45:30\"");
        assertThat(json).doesNotContain("optionalNote");

        SamplePayload parsed = objectMapper.readValue(
                "{\"createdAt\":\"2026-11-26 09:45:30\"}", SamplePayload.class);
        assertThat(parsed.createdAt()).isEqualTo(ts);
        assertThat(parsed.optionalNote()).isNull();
    }

    @Test
    void jwtServiceRoundTripsWithPureJackson3Codec() {
        User user = new User();
        user.setId(42L);
        user.setUsername("ops-boot4");
        user.setRole(Role.OPS);

        String token = jwtService.issue(user);
        Optional<UserPrincipal> parsed = jwtService.parse(token);

        assertThat(parsed).isPresent();
        assertThat(parsed.get().id()).isEqualTo(42L);
        assertThat(parsed.get().username()).isEqualTo("ops-boot4");
        assertThat(parsed.get().role()).isEqualTo(Role.OPS);
        assertThat(jwtService.parse(token + "tampered")).isEmpty();
        assertThat(jwtService.parse("   ")).isEmpty();
    }
}
