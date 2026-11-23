package io.tieringkv.security.rpc;

import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;

/** RPC 权限守卫（ADR-0110）：按调用类型要求令牌权限域。 */
public final class RpcPermissionGuard {

    private final CredentialManager credentials;

    public RpcPermissionGuard(CredentialManager credentials) {
        this.credentials = credentials;
    }

    public void require(String token, String rpcType) {
        Permission permission = permissionFor(rpcType);
        credentials.require(token, permission);
    }

    public static Permission permissionFor(String rpcType) {
        return switch (rpcType) {
            case "TXN_GET" -> Permission.READ;
            case "TXN_PREWRITE", "TXN_COMMIT", "TXN_ROLLBACK" ->
                    Permission.WRITE;
            case "META_PROPOSE", "META_STATUS" -> Permission.ADMIN;
            case "BACKUP" -> Permission.BACKUP;
            case "CDC" -> Permission.CDC;
            default -> throw new IllegalArgumentException(
                    "unknown rpc type " + rpcType);
        };
    }
}
