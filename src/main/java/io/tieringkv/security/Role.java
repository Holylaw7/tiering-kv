package io.tieringkv.security;

import java.util.EnumSet;
import java.util.Set;

/** 内置角色（ADR-0106）：角色到权限集合的映射。 */
public enum Role {
    READER(EnumSet.of(Permission.READ)),
    WRITER(EnumSet.of(Permission.READ, Permission.WRITE)),
    ADMIN(EnumSet.allOf(Permission.class)),
    BACKUP_OPERATOR(EnumSet.of(Permission.READ, Permission.BACKUP)),
    CDC_CONSUMER(EnumSet.of(Permission.READ, Permission.CDC));

    private final Set<Permission> permissions;

    Role(Set<Permission> permissions) {
        this.permissions = Set.copyOf(permissions);
    }

    public Set<Permission> permissions() {
        return permissions;
    }

    public boolean allows(Permission permission) {
        return permissions.contains(permission);
    }
}
