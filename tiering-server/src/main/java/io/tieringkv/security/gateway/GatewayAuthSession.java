package io.tieringkv.security.gateway;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;

/** 网关认证会话（ADR-0110）：AUTH 绑定连接与角色。 */
public final class GatewayAuthSession {

    private final CredentialManager credentials;
    private volatile String token;
    private volatile Role role;

    public GatewayAuthSession(CredentialManager credentials) {
        this.credentials = credentials;
    }

    public boolean authenticate(String token) {
        Role role = credentials.validate(token);
        this.token = token;
        this.role = role;
        return true;
    }

    public boolean isAuthenticated() {
        if (role == null) {
            return false;
        }
        try {
            credentials.validate(token);
            return true;
        } catch (SecurityException e) {
            role = null;
            token = null;
            return false;
        }
    }

    public Role role() {
        return isAuthenticated() ? role : null;
    }

    public void logout() {
        token = null;
        role = null;
    }
}
