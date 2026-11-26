package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.domain.Role;

/** 认证上下文中的当前用户（来自 JWT 声明，含令牌版本号用于即时吊销）。 */
public record UserPrincipal(Long id, String username, Role role, long tokenVersion) {

    public UserPrincipal(Long id, String username, Role role) {
        this(id, username, role, 0L);
    }

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
