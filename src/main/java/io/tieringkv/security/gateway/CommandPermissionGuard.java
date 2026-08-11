package io.tieringkv.security.gateway;

import io.tieringkv.security.Permission;
import io.tieringkv.security.Role;

/** 命令级权限守卫（ADR-0110）：命令 → 所需权限域。 */
public final class CommandPermissionGuard {

    private CommandPermissionGuard() {
    }

    public static Permission required(String command) {
        return switch (command.toUpperCase()) {
            case "GET", "EXISTS", "MGET" -> Permission.READ;
            case "SET", "DEL", "MSET" -> Permission.WRITE;
            case "INFO", "CLUSTER" -> Permission.ADMIN;
            default -> throw new IllegalArgumentException(
                    "unknown command " + command);
        };
    }

    public static boolean allows(Role role, String command) {
        return role != null && role.allows(required(command));
    }

    public static void require(Role role, String command) {
        if (role == null) {
            throw new SecurityException("unauthenticated");
        }
        Permission permission = required(command);
        if (!role.allows(permission)) {
            throw new SecurityException(
                    "permission denied: " + permission
                            + " for " + command);
        }
    }
}
