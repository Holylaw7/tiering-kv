package io.tieringkv.security;

import io.tieringkv.security.gateway.CommandPermissionGuard;
import io.tieringkv.security.gateway.GatewayAuthSession;
import io.tieringkv.security.rpc.RpcPermissionGuard;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RBAC 网关/RPC 接线（ADR-0110）：AUTH、命令权限、RPC 守卫。 */
class GatewayRbacIntegrationTest {

    @Test
    void authBindsRole() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        assertThat(session.authenticate(token)).isTrue();
        assertThat(session.role()).isEqualTo(Role.WRITER);
    }

    @Test
    void unauthenticatedRejectsCommands() {
        GatewayAuthSession session = new GatewayAuthSession(
                new CredentialManager());
        assertThat(session.isAuthenticated()).isFalse();
        assertThat(session.role()).isNull();
    }

    @Test
    void wrongTokenRejected() {
        GatewayAuthSession session = new GatewayAuthSession(
                new CredentialManager());
        assertThatThrownBy(() -> session.authenticate("bad"))
                .isInstanceOf(SecurityException.class);
        assertThat(session.isAuthenticated()).isFalse();
    }

    @Test
    void expiredSessionInvalidated() throws Exception {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.READER, 1);
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        session.authenticate(token);
        Thread.sleep(5);
        assertThat(session.isAuthenticated()).isFalse();
    }

    @Test
    void logoutClearsSession() {
        CredentialManager credentials = new CredentialManager();
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        session.authenticate(credentials.issue(Role.ADMIN, 60_000));
        session.logout();
        assertThat(session.isAuthenticated()).isFalse();
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"GET", "EXISTS", "MGET"})
    void readerAllowsReadCommands(String command) {
        assertThat(CommandPermissionGuard.allows(Role.READER, command))
                .isTrue();
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"SET", "DEL", "MSET"})
    void readerRejectsWriteCommands(String command) {
        assertThat(CommandPermissionGuard.allows(Role.READER, command))
                .isFalse();
        assertThatThrownBy(() ->
                CommandPermissionGuard.require(Role.READER, command))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"SET", "DEL", "MSET"})
    void writerAllowsWriteCommands(String command) {
        assertThat(CommandPermissionGuard.allows(Role.WRITER, command))
                .isTrue();
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"INFO", "CLUSTER"})
    void writerRejectsAdminCommands(String command) {
        assertThat(CommandPermissionGuard.allows(Role.WRITER, command))
                .isFalse();
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"INFO", "CLUSTER"})
    void adminAllowsAdminCommands(String command) {
        assertThat(CommandPermissionGuard.allows(Role.ADMIN, command))
                .isTrue();
    }

    @Test
    void unknownCommandRejected() {
        assertThatThrownBy(() ->
                CommandPermissionGuard.required("BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void rpcGuardAllowsRead() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.READER, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        guard.require(token, "TXN_GET");
    }

    @Test
    void rpcGuardRejectsWriteForReader() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.READER, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        assertThatThrownBy(() -> guard.require(token, "TXN_PREWRITE"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rpcGuardAdminForMeta() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.ADMIN, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        guard.require(token, "META_PROPOSE");
    }

    @Test
    void rpcGuardBackupPermission() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.BACKUP_OPERATOR, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        guard.require(token, "BACKUP");
        assertThatThrownBy(() -> guard.require(token, "CDC"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rpcGuardCdcPermission() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.CDC_CONSUMER, 60_000);
        RpcPermissionGuard guard = new RpcPermissionGuard(credentials);
        guard.require(token, "CDC");
        assertThatThrownBy(() -> guard.require(token, "TXN_PREWRITE"))
                .isInstanceOf(SecurityException.class);
    }

    @Test
    void rpcGuardUnknownTypeRejected() {
        RpcPermissionGuard guard = new RpcPermissionGuard(
                new CredentialManager());
        assertThatThrownBy(() -> guard.require("token", "BOGUS"))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void sessionRoleAllowsCommandEndToEnd() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.WRITER, 60_000);
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        session.authenticate(token);
        CommandPermissionGuard.require(session.role(), "SET");
        assertThatThrownBy(() ->
                CommandPermissionGuard.require(session.role(), "CLUSTER"))
                .isInstanceOf(SecurityException.class);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 10, 100})
    void parameterizedAuthSessions(int count) {
        CredentialManager credentials = new CredentialManager();
        for (int i = 0; i < count; i++) {
            GatewayAuthSession session = new GatewayAuthSession(
                    credentials);
            session.authenticate(credentials.issue(Role.READER, 60_000));
            assertThat(session.isAuthenticated()).isTrue();
        }
    }
}
