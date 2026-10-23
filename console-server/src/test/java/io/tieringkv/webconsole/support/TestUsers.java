package io.tieringkv.webconsole.support;

import io.tieringkv.webconsole.domain.Role;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.UserRepository;
import org.springframework.security.crypto.password.PasswordEncoder;

/** 测试夹具：确保存在指定角色的账号。 */
public final class TestUsers {

    private TestUsers() {
    }

    public static User ensure(UserRepository repository, PasswordEncoder encoder,
                              String username, String rawPassword, Role role) {
        return repository.findByUsername(username).orElseGet(() -> {
            User user = new User();
            user.setUsername(username);
            user.setPassword(encoder.encode(rawPassword));
            user.setNickname(username);
            user.setRole(role);
            user.setStatus(1);
            return repository.save(user);
        });
    }
}
