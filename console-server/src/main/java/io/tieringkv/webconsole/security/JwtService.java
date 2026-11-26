package io.tieringkv.webconsole.security;

import io.jsonwebtoken.Claims;
import io.jsonwebtoken.JwtException;
import io.jsonwebtoken.Jwts;
import io.jsonwebtoken.io.AbstractDeserializer;
import io.jsonwebtoken.io.AbstractSerializer;
import io.jsonwebtoken.io.Deserializer;
import io.jsonwebtoken.io.Serializer;
import io.jsonwebtoken.security.Keys;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import org.springframework.stereotype.Service;
import tools.jackson.core.StreamReadFeature;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

import javax.crypto.SecretKey;
import java.io.OutputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Date;
import java.util.Map;
import java.util.Optional;

/** JWT 签发与校验（HS256，基于 Jackson 3 编解码，无 Jackson 2 传递依赖）。 */
@Service
public class JwtService {

    private static final ObjectMapper JWT_MAPPER = JsonMapper.builder()
            .disable(StreamReadFeature.AUTO_CLOSE_SOURCE)
            .build();

    private static final Serializer<Map<String, ?>> JWT_SERIALIZER = new AbstractSerializer<>() {
        @Override
        protected void doSerialize(Map<String, ?> map, OutputStream out) throws Exception {
            out.write(JWT_MAPPER.writeValueAsBytes(map));
        }
    };

    private static final Deserializer<Map<String, ?>> JWT_DESERIALIZER = new AbstractDeserializer<>() {
        @Override
        @SuppressWarnings("unchecked")
        protected Map<String, ?> doDeserialize(Reader reader) {
            return JWT_MAPPER.readValue(reader, Map.class);
        }
    };

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
                .json(JWT_SERIALIZER)
                .subject(user.getUsername())
                .claim("uid", user.getId())
                .claim("role", user.getRole().name())
                .claim("ver", user.getTokenVersion())
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
                    .json(JWT_DESERIALIZER)
                    .verifyWith(key)
                    .build()
                    .parseSignedClaims(token)
                    .getPayload();
            Long uid = claims.get("uid", Number.class) == null
                    ? null : claims.get("uid", Number.class).longValue();
            String role = claims.get("role", String.class);
            Number verClaim = claims.get("ver", Number.class);
            long tokenVersion = verClaim == null ? 0L : verClaim.longValue();
            if (uid == null || role == null) {
                return Optional.empty();
            }
            return Optional.of(new UserPrincipal(uid, claims.getSubject(), Role.valueOf(role), tokenVersion));
        } catch (JwtException | IllegalArgumentException ex) {
            return Optional.empty();
        }
    }
}
