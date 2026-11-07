package io.tieringkv.webconsole.config;

import io.tieringkv.webconsole.config.ConsoleProperties;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.InitializingBean;
import org.springframework.context.annotation.Profile;
import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * 生产配置安全启动门禁（ADR-0368）。
 *
 * <p>只在 prod / production profile 生效，在 bean 初始化阶段校验；Tomcat 在上下文刷新末尾才启动，
 * 因此这里的失败发生在服务对外可用之前。日志只输出配置项名称与原因，绝不输出密钥或口令。
 */
@Component
@Profile({"prod", "production"})
public class ProductionConfigGuard implements InitializingBean {

    private static final Logger log = LoggerFactory.getLogger(ProductionConfigGuard.class);

    /** 已知开发默认密钥：出现即视为未配置。 */
    public static final String DEFAULT_JWT_SECRET =
            "tiering-kv-console-dev-secret-key-change-me-please-0123456789";

    private static final Set<String> WEAK_DB_PASSWORDS = Set.of(
            "root", "password", "changeme", "change-me", "admin123", "123456", "12345678");

    private final ConsoleProperties properties;
    private final Environment environment;

    public ProductionConfigGuard(ConsoleProperties properties, Environment environment) {
        this.properties = properties;
        this.environment = environment;
    }

    /** 一条检查结果：只含配置项名称与原因，不含值。 */
    public record Finding(String level, String key, String message) {
    }

    @Override
    public void afterPropertiesSet() {
        List<Finding> findings = check();
        for (Finding f : findings) {
            switch (f.level()) {
                case "FATAL" -> log.error("生产配置不通过：{} - {}", f.key(), f.message());
                case "WARN" -> log.warn("生产配置提醒：{} - {}", f.key(), f.message());
                default -> log.info("生产配置：{} - {}", f.key(), f.message());
            }
        }
        List<String> fatalKeys = findings.stream()
                .filter(f -> "FATAL".equals(f.level()))
                .map(Finding::key)
                .toList();
        if (!fatalKeys.isEmpty()) {
            throw new IllegalStateException("生产配置校验失败，拒绝启动：" + String.join(", ", fatalKeys));
        }
        log.info("生产配置校验通过（{} 项）", findings.size());
    }

    /** 纯函数式校验，便于单测；不读取任何需要网络或数据库的资源。 */
    public List<Finding> check() {
        List<Finding> out = new ArrayList<>();
        checkJwt(out);
        checkDatabase(out);
        checkAdmin(out);
        if (out.stream().noneMatch(f -> "FATAL".equals(f.level()))) {
            out.add(new Finding("INFO", "production-gate", "所有强制项通过"));
        }
        return out;
    }

    private void checkJwt(List<Finding> out) {
        String secret = properties.getJwt() == null ? null : properties.getJwt().getSecret();
        String key = "console.jwt.secret";
        if (secret == null || secret.isBlank()) {
            out.add(new Finding("FATAL", key, "未配置"));
            return;
        }
        if (DEFAULT_JWT_SECRET.equals(secret)) {
            out.add(new Finding("FATAL", key, "仍为开发默认值"));
            return;
        }
        if (secret.getBytes(StandardCharsets.UTF_8).length < 32) {
            out.add(new Finding("FATAL", key, "长度不足 32 字节，不满足 HS256 密钥要求"));
            return;
        }
        long distinct = secret.chars().distinct().count();
        if (distinct < 10) {
            out.add(new Finding("FATAL", key, "字符多样性不足（疑似重复字符构造的弱密钥）"));
            return;
        }
        String lower = secret.toLowerCase();
        if (lower.contains("change-me") || lower.contains("please-change") || lower.contains("example")) {
            out.add(new Finding("FATAL", key, "包含示例占位文本"));
            return;
        }
        out.add(new Finding("INFO", key, "已配置，长度与多样性满足要求"));
    }

    private void checkDatabase(List<Finding> out) {
        String password = environment.getProperty("spring.datasource.password", "");
        String user = environment.getProperty("spring.datasource.username", "");
        String url = environment.getProperty("spring.datasource.url", "");
        if (password == null || password.isBlank()) {
            out.add(new Finding("FATAL", "spring.datasource.password", "数据库口令为空"));
        } else if (WEAK_DB_PASSWORDS.contains(password.toLowerCase())) {
            out.add(new Finding("FATAL", "spring.datasource.password", "使用了示例/弱口令"));
        } else {
            out.add(new Finding("INFO", "spring.datasource.password", "已配置"));
        }
        if ("root".equalsIgnoreCase(user)) {
            out.add(new Finding("WARN", "spring.datasource.username", "使用 root 账户，建议最小权限账户"));
        }
        if (url != null && (url.contains("127.0.0.1") || url.contains("localhost"))) {
            out.add(new Finding("WARN", "spring.datasource.url", "指向本机地址，确认是否符合部署拓扑"));
        }
    }

    private void checkAdmin(List<Finding> out) {
        String password = properties.getBootstrap() == null ? null : properties.getBootstrap().getAdminPassword();
        String key = "CONSOLE_ADMIN_PASSWORD";
        if (password == null || password.isBlank()) {
            out.add(new Finding("FATAL", key, "未配置初始管理员口令"));
            return;
        }
        if (password.equalsIgnoreCase("admin123")) {
            out.add(new Finding("FATAL", key, "仍为默认口令"));
            return;
        }
        if (password.length() < 12) {
            out.add(new Finding("FATAL", key, "长度不足 12 位"));
            return;
        }
        out.add(new Finding("INFO", key, "已配置"));
    }
}
