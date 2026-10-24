package io.tieringkv.webconsole.config;

import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.DbInstanceRepository;
import io.tieringkv.webconsole.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

import java.util.Optional;

/**
 * 首次启动引导：
 * <ol>
 *   <li>无用户时创建默认管理员；</li>
 *   <li>按 {@code console.bootstrap.instance}（形如
 *       {@code name@host:port}）修正默认实例地址——容器部署时数据面在
 *       另一个容器里，127.0.0.1 不可达。</li>
 * </ol>
 */
@Component
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private final UserRepository userRepository;
    private final DbInstanceRepository instanceRepository;
    private final PasswordEncoder passwordEncoder;
    private final ConsoleProperties properties;

    public BootstrapDataInitializer(UserRepository userRepository,
                                    DbInstanceRepository instanceRepository,
                                    PasswordEncoder passwordEncoder,
                                    ConsoleProperties properties) {
        this.userRepository = userRepository;
        this.instanceRepository = instanceRepository;
        this.passwordEncoder = passwordEncoder;
        this.properties = properties;
    }

    @Override
    public void run(ApplicationArguments args) {
        ensureAdmin();
        applyInstanceOverride();
    }

    void ensureAdmin() {
        if (userRepository.count() > 0) {
            return;
        }
        User admin = new User();
        admin.setUsername("admin");
        admin.setPassword(passwordEncoder.encode("admin123"));
        admin.setNickname("系统管理员");
        admin.setRole(Role.ADMIN);
        admin.setStatus(1);
        userRepository.save(admin);
        log.warn("已创建默认管理员 admin / admin123，请立即登录后修改口令");
    }

    /**
     * 按配置修正/创建默认实例。配置为空表示沿用 Flyway 种子的本机实例。
     *
     * @return 是否发生了写库
     */
    boolean applyInstanceOverride() {
        String spec = properties.getBootstrap().getInstance();
        if (spec == null || spec.isBlank()) {
            return false;
        }
        String name = "local-6379";
        String hostPort = spec.trim();
        int at = hostPort.indexOf('@');
        if (at > 0) {
            name = hostPort.substring(0, at).trim();
            hostPort = hostPort.substring(at + 1).trim();
        }
        int colon = hostPort.lastIndexOf(':');
        String host = colon > 0 ? hostPort.substring(0, colon) : hostPort;
        int port = 6379;
        if (colon > 0) {
            try {
                port = Integer.parseInt(hostPort.substring(colon + 1));
            } catch (NumberFormatException ex) {
                log.warn("console.bootstrap.instance 端口非法：{}，使用 6379", spec);
            }
        }
        Optional<DbInstance> existing = instanceRepository.findByName(name);
        DbInstance instance = existing.orElseGet(DbInstance::new);
        boolean changed = existing.isEmpty()
                || !host.equals(instance.getHost())
                || port != instance.getPort();
        if (!changed) {
            return false;
        }
        instance.setName(name);
        instance.setHost(host);
        instance.setPort(port);
        if (existing.isEmpty()) {
            instance.setRemark("容器引导创建");
            instance.setEnabled(true);
        }
        instanceRepository.save(instance);
        log.info("默认实例已指向 {}:{}（name={}）", host, port, name);
        return true;
    }
}
