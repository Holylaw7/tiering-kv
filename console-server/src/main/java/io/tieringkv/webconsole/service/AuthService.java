package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.security.JwtService;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.web.dto.LoginRequest;
import io.tieringkv.webconsole.web.dto.LoginResponse;
import io.tieringkv.webconsole.web.dto.UserView;
import org.springframework.security.crypto.password.PasswordEncoder;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;

/** 认证服务：口令校验 + 令牌签发。 */
@Service
public class AuthService {

    private final UserRepository userRepository;
    private final PasswordEncoder passwordEncoder;
    private final JwtService jwtService;
    private final AuditService auditService;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        User user = userRepository.findByUsername(request.username().trim())
                .orElseThrow(() -> BusinessException.unauthorized("用户名或密码错误"));
        if (user.getStatus() != 1) {
            auditService.record("LOGIN", user.getUsername(), "账号已禁用", false);
            throw BusinessException.unauthorized("账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            auditService.record("LOGIN", user.getUsername(), "口令错误", false);
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        String token = jwtService.issue(user);
        auditService.record("LOGIN", user.getUsername(), "登录成功", true);
        return new LoginResponse(token, jwtService.ttl().toSeconds(), UserView.of(user));
    }

    public UserView currentUser() {
        return userRepository.findById(SecurityUtils.currentUser().id())
                .map(UserView::of)
                .orElseThrow(() -> BusinessException.unauthorized("用户不存在"));
    }
}
