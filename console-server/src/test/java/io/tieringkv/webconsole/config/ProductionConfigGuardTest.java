package io.tieringkv.webconsole.config;

import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * G0-3 / ADR-0368：生产配置安全门禁。
 *
 * <p>每个用例对应一条"错误配置不允许进入可服务状态"的断言；
 * 同时固定"日志与异常消息不得泄露密钥"这一约束。
 */
class ProductionConfigGuardTest {

    private static final String STRONG = "Zk8Qv2LmN7pR4tXyA9bC3dE6fG1hJ5kM0nP8sU2w";

    private ConsoleProperties props(String secret, String adminPassword) {
        ConsoleProperties p = new ConsoleProperties();
        p.getJwt().setSecret(secret);
        p.getBootstrap().setAdminPassword(adminPassword);
        return p;
    }

    private MockEnvironment env(String password) {
        return new MockEnvironment()
                .withProperty("spring.datasource.password", password)
                .withProperty("spring.datasource.username", "console_app")
                .withProperty("spring.datasource.url", "jdbc:mysql://db.internal:3306/tiering_console");
    }

    private ProductionConfigGuard guard(String secret, String dbPassword, String adminPassword) {
        return new ProductionConfigGuard(props(secret, adminPassword), env(dbPassword));
    }

    private List<String> fatalKeys(ProductionConfigGuard g) {
        return g.check().stream().filter(f -> "FATAL".equals(f.level())).map(f -> f.key()).toList();
    }

    @Test
    void 缺少JWT密钥时启动失败() {
        assertThat(fatalKeys(guard(null, "S3cure-db-pass", "AdminPass!2345"))).contains("console.jwt.secret");
        assertThatThrownBy(() -> guard(null, "S3cure-db-pass", "AdminPass!2345").afterPropertiesSet())
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("console.jwt.secret");
    }

    @Test
    void 使用默认JWT密钥时启动失败() {
        assertThat(fatalKeys(guard(ProductionConfigGuard.DEFAULT_JWT_SECRET,
                "S3cure-db-pass", "AdminPass!2345"))).contains("console.jwt.secret");
    }

    @Test
    void 弱JWT密钥时启动失败() {
        assertThat(fatalKeys(guard("short-key", "S3cure-db-pass", "AdminPass!2345")))
                .contains("console.jwt.secret");
        assertThat(fatalKeys(guard("aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa",
                "S3cure-db-pass", "AdminPass!2345"))).contains("console.jwt.secret");
        assertThat(fatalKeys(guard("Zk8Qv2LmN7pR4tXyA9bC3dE6fG1hJ5kM0nP8sU2w-change-me",
                "S3cure-db-pass", "AdminPass!2345"))).contains("console.jwt.secret");
    }

    @Test
    void 合法随机密钥可以通过() {
        assertThat(fatalKeys(guard(STRONG, "S3cure-db-pass", "AdminPass!2345"))).isEmpty();
        assertThatCode(() -> guard(STRONG, "S3cure-db-pass", "AdminPass!2345").afterPropertiesSet())
                .doesNotThrowAnyException();
    }

    @Test
    void 数据库口令为空或示例值时启动失败() {
        assertThat(fatalKeys(guard(STRONG, "", "AdminPass!2345")))
                .contains("spring.datasource.password");
        assertThat(fatalKeys(guard(STRONG, "root", "AdminPass!2345")))
                .contains("spring.datasource.password");
    }

    @Test
    void 默认管理员口令时启动失败() {
        assertThat(fatalKeys(guard(STRONG, "S3cure-db-pass", null))).contains("CONSOLE_ADMIN_PASSWORD");
        assertThat(fatalKeys(guard(STRONG, "S3cure-db-pass", "admin123")))
                .contains("CONSOLE_ADMIN_PASSWORD");
        assertThat(fatalKeys(guard(STRONG, "S3cure-db-pass", "short")))
                .contains("CONSOLE_ADMIN_PASSWORD");
    }

    @Test
    void 异常消息与检查结果都不包含密钥内容() {
        String secret = ProductionConfigGuard.DEFAULT_JWT_SECRET;
        ProductionConfigGuard g = guard(secret, "root", "admin123");
        String text = g.check().toString();
        assertThat(text).doesNotContain(secret).doesNotContain("admin123").doesNotContain("root");

        String message = org.assertj.core.api.Assertions.catchThrowableOfType(
                IllegalStateException.class, () -> g.afterPropertiesSet()).getMessage();
        assertThat(message).doesNotContain(secret).doesNotContain("admin123");
    }

    @Test
    void 生产配置校验只产生FATAL与INFO或WARN三类结果() {
        List<String> levels = guard(STRONG, "S3cure-db-pass", "AdminPass!2345").check().stream()
                .map(f -> f.level()).toList();
        assertThat(levels).allMatch(l -> List.of("FATAL", "WARN", "INFO").contains(l));
    }

    @Test
    void 静态加密主密钥默认WARN且严格模式下阻断启动() {
        // 默认未配置 encryption-key：输出 WARN 但不阻断启动（向后兼容）
        ProductionConfigGuard compat = guard(STRONG, "S3cure-db-pass", "AdminPass!2345");
        assertThat(compat.check()).anyMatch(f ->
                "WARN".equals(f.level()) && "console.security.encryption-key".equals(f.key()));
        assertThatCode(compat::afterPropertiesSet).doesNotThrowAnyException();

        // 开启严格模式且未配置：升级为 FATAL 阻断启动
        ConsoleProperties strictProps = props(STRONG, "AdminPass!2345");
        strictProps.getSecurity().setRequireEncryptionInProd(true);
        ProductionConfigGuard strict = new ProductionConfigGuard(strictProps, env("S3cure-db-pass"));
        assertThat(fatalKeys(strict)).contains("console.security.encryption-key");

        // 密钥过短（< 16 字节）：直接 FATAL
        ConsoleProperties shortProps = props(STRONG, "AdminPass!2345");
        shortProps.getSecurity().setEncryptionKey("too-short");
        assertThat(fatalKeys(new ProductionConfigGuard(shortProps, env("S3cure-db-pass"))))
                .contains("console.security.encryption-key");

        // 合法密钥（>= 16 字节）：通过
        ConsoleProperties validProps = props(STRONG, "AdminPass!2345");
        validProps.getSecurity().setRequireEncryptionInProd(true);
        validProps.getSecurity().setEncryptionKey("Prod-Master-Encryption-Key-2026!");
        assertThat(fatalKeys(new ProductionConfigGuard(validProps, env("S3cure-db-pass")))).isEmpty();
    }
}
