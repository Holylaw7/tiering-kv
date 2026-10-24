package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.domain.Role;

/** 认证上下文中的当前用户（来自 JWT 声明）。 */
public record UserPrincipal(Long id, String username, Role role) {

    public boolean isAdmin() {
        return role == Role.ADMIN;
    }
}
