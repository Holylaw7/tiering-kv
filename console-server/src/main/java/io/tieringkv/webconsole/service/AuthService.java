package io.tieringkv.webconsole.service;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.domain.User;
import io.tieringkv.webconsole.repository.UserRepository;
import io.tieringkv.webconsole.security.JwtService;
import io.tieringkv.webconsole.security.LoginAttemptGuard;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.security.SecurityUtils;
import io.tieringkv.webconsole.security.UserPrincipal;
import io.tieringkv.webconsole.web.dto.ChangePasswordRequest;
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
    private final LoginAttemptGuard attemptGuard;

    public AuthService(UserRepository userRepository, PasswordEncoder passwordEncoder,
                       JwtService jwtService, AuditService auditService,
                       LoginAttemptGuard attemptGuard) {
        this.userRepository = userRepository;
        this.passwordEncoder = passwordEncoder;
        this.jwtService = jwtService;
        this.auditService = auditService;
        this.attemptGuard = attemptGuard;
    }

    @Transactional
    public LoginResponse login(LoginRequest request) {
        String username = request.username().trim();
        if (attemptGuard.blocked(username)) {
            auditService.record("LOGIN", username, "失败次数过多，已限流", false);
            throw BusinessException.tooManyRequests(
                    "登录失败次数过多，请 " + attemptGuard.remainingSeconds(username) + " 秒后再试");
        }
        User user = userRepository.findByUsername(username)
                .orElseThrow(() -> {
                    attemptGuard.recordFailure(username);
                    return BusinessException.unauthorized("用户名或密码错误");
                });
        if (user.getStatus() != 1) {
            auditService.record("LOGIN", user.getUsername(), "账号已禁用", false);
            throw BusinessException.unauthorized("账号已被禁用，请联系管理员");
        }
        if (!passwordEncoder.matches(request.password(), user.getPassword())) {
            attemptGuard.recordFailure(username);
            auditService.record("LOGIN", user.getUsername(), "口令错误", false);
            throw BusinessException.unauthorized("用户名或密码错误");
        }
        attemptGuard.recordSuccess(username);
        user.setLastLoginAt(LocalDateTime.now());
        userRepository.save(user);
        String token = jwtService.issue(user);
        auditService.record("LOGIN", user.getUsername(), "登录成功", true);
        return new LoginResponse(token, jwtService.ttl().toSeconds(), UserView.of(user));
    }

    /**
     * 自助修改口令（TD-087）：校验原口令 → 更新散列 → 审计留痕。
     *
     * <p>注意：JWT 无状态，已签发的令牌在过期前仍然有效（限制已记录在
     * 发布说明中）；如需即时失效需引入令牌版本号。
     */
    @Transactional
    public void changePassword(ChangePasswordRequest request) {
        UserPrincipal principal = SecurityUtils.currentUser();
        User user = userRepository.findById(principal.id())
                .orElseThrow(() -> BusinessException.unauthorized("用户不存在"));
        if (!passwordEncoder.matches(request.oldPassword(), user.getPassword())) {
            auditService.record("PASSWORD_CHANGE", user.getUsername(), "原口令错误", false);
            throw BusinessException.badRequest("原口令不正确");
        }
        if (passwordEncoder.matches(request.newPassword(), user.getPassword())) {
            throw BusinessException.badRequest("新口令不能与原口令相同");
        }
        user.setPassword(passwordEncoder.encode(request.newPassword()));
        userRepository.save(user);
        attemptGuard.recordSuccess(user.getUsername());
        auditService.record("PASSWORD_CHANGE", user.getUsername(), "自助修改", true);
    }

    public UserView currentUser() {
        return userRepository.findById(SecurityUtils.currentUser().id())
                .map(UserView::of)
                .orElseThrow(() -> BusinessException.unauthorized("用户不存在"));
    }
}
