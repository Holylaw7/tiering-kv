package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.common.BusinessException;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;

/** 当前登录用户访问器。 */
public final class SecurityUtils {

    private SecurityUtils() {
    }

    public static UserPrincipal currentUser() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication == null || !(authentication.getPrincipal() instanceof UserPrincipal principal)) {
            throw BusinessException.unauthorized("未登录或登录已过期");
        }
        return principal;
    }

    public static String currentUsername() {
        return currentUser().username();
    }
}
