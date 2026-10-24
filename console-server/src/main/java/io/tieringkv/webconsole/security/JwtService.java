package io.tieringkv.webconsole.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.security.Keys;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import org.springframework.stereotype.Service;

import javax.crypto.SecretKey;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;

/** JWT 签发与校验（HS256）。 */
@Service
public class JwtService {

    private final SecretKey key;
    private final Duration ttl;

    public JwtService(ConsoleProperties properties) {
        String secret = properties.getJwt().getSecret();
        byte[] bytes = secret == null ? new byte[0] : secret.getBytes(StandardCharsets.UTF_8);
        if (bytes.length < 32) {
            throw new IllegalStateException("console.jwt.secret 至少需要 32 字节（HS256 要求）");
        }
        this.key = Keys.hmacShaKeyFor(bytes);
        this.ttl = Duration.ofMinutes(properties.getJwt().getTtlMinutes());
    }

    public Duration ttl() {
        return ttl;
    }

    public String issue(User user) {
        Instant now = Instant.now();
        return Jwts.builder()
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .issuedAt(Date.from(now))
                .expiration(Date.from(now.plus(ttl)))
                .signWith(key, Jwts.SIG.HS256)
                .compact();
    }

    public Optional<UserPrincipal> parse(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        try {
            Claims claims = Jwts.parser()
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long uid = claims.get("uid", Number.class) == null
                    ? null : claims.get("uid", Number.class).longValue();
            String role = claims.get("role", String.class);
            if (uid == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new UserPrincipal(uid, claims.getSubject(), Role.valueOf(role)));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
