package io.tieringkv.webconsole.web;

import io.tieringkv.webconsole.common.ApiResponse;
import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.security.ClientIpResolver;
import io.tieringkv.webconsole.service.AuthService;
import io.tieringkv.webconsole.web.dto.ChangePasswordRequest;
import io.tieringkv.webconsole.web.dto.LoginRequest;
import io.tieringkv.webconsole.web.dto.LoginResponse;
import io.tieringkv.webconsole.web.dto.UserView;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/** 认证接口。 */
@RestController
@RequestMapping("/api/v1/auth")
public class AuthController {

    private final AuthService authService;
    private final ConsoleProperties properties;

    public AuthController(AuthService authService) {
        this(authService, new ConsoleProperties());
    }

    @Autowired
    public AuthController(AuthService authService, ConsoleProperties properties) {
        this.authService = authService;
        this.properties = properties;
    }

    @PostMapping("/login")
    public ApiResponse<LoginResponse> login(@Valid @RequestBody LoginRequest request,
                                            HttpServletRequest httpRequest) {
        String clientIp = ClientIpResolver.resolve(httpRequest,
                properties.getSecurity().getTrustedProxies());
        return ApiResponse.ok(authService.login(request, clientIp));
    }

    @GetMapping("/me")
    public ApiResponse<UserView> me() {
        return ApiResponse.ok(authService.currentUser());
    }

    /** 自助修改口令（任意已登录用户；修改成功后立即吊销旧 JWT）。 */
    @PostMapping("/password")
    public ApiResponse<Void> changePassword(
            @Valid @RequestBody ChangePasswordRequest request) {
        authService.changePassword(request);
        return ApiResponse.ok();
    }

    /** 主动登出并吊销当前用户全部活跃令牌（v6.1.0 A2）。 */
    @PostMapping("/logout")
    public ApiResponse<Void> logout() {
        authService.logout();
        return ApiResponse.ok();
    }
}
