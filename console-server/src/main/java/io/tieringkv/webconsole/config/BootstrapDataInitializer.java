package io.tieringkv.webconsole.config;

import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.UserRepository;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Component;

/** 首次启动引导：无用户时创建默认管理员。 */
@Component
public class BootstrapDataInitializer implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(BootstrapDataInitializer.class);

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;

    public BootstrapDataInitializer(UserRepository userRepository,
                                    PasswordEncoder passwordEncoder) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
    }

    @Override
    public void run(ApplicationArguments args) {
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
}
