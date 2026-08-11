package io.tieringkv.security;

import io.tieringkv.backup.pitr.PitrWriteLog;
import io.tieringkv.cdc.CDCProducer;
import io.tieringkv.cdc.CDCConsumerRegistry;
import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.security.gateway.CommandPermissionGuard;
import io.tieringkv.security.gateway.GatewayAuthSession;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** RBAC/PITR/CDC 组合边缘（ADR-0110/0111/0112）。 */
class RbacPitrCdcEdgeTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "role {0} command {1}")
    @ValueSource(strings = {"READER GET", "WRITER SET", "ADMIN INFO",
            "BACKUP_OPERATOR GET", "CDC_CONSUMER GET"})
    void rbacAllowMatrix(String pair) {
        String[] parts = pair.split(" ");
        Role role = Role.valueOf(parts[0]);
        String command = parts[1];
        CredentialManager credentials = new CredentialManager();
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        session.authenticate(credentials.issue(role, 60_000));
        assertThat(CommandPermissionGuard.allows(session.role(), command))
                .isTrue();
    }

    @ParameterizedTest(name = "role {0} command {1}")
    @ValueSource(strings = {"READER SET", "WRITER INFO",
            "BACKUP_OPERATOR SET", "CDC_CONSUMER SET"})
    void rbacDenyMatrix(String pair) {
        String[] parts = pair.split(" ");
        Role role = Role.valueOf(parts[0]);
        String command = parts[1];
        assertThat(CommandPermissionGuard.allows(role, command))
                .isFalse();
    }

    @ParameterizedTest(name = "rpc {0}")
    @ValueSource(strings = {"TXN_GET", "TXN_PREWRITE", "META_PROPOSE",
            "BACKUP", "CDC"})
    void rpcPermissionMap(String rpcType) {
        assertThat(io.tieringkv.security.rpc.RpcPermissionGuard
                .permissionFor(rpcType)).isNotNull();
    }

    @Test
    void adminAllRpcAllowed() {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.ADMIN, 60_000);
        io.tieringkv.security.rpc.RpcPermissionGuard guard =
                new io.tieringkv.security.rpc.RpcPermissionGuard(
                        credentials);
        for (String type : new String[]{"TXN_GET", "TXN_PREWRITE",
                "META_PROPOSE", "BACKUP", "CDC"}) {
            guard.require(token, type);
        }
    }

    @ParameterizedTest(name = "segments {0}")
    @ValueSource(ints = {2, 8, 20})
    void pitrRetentionParameterized(int segments) throws Exception {
        Path archive = dir.resolve("ret-" + segments);
        PitrWriteLog log = PitrWriteLog.open(archive, 1);
        for (int i = 0; i < segments; i++) {
            log.append(new io.tieringkv.backup.pitr.PitrRecord(i, i,
                    i * 10, bytes("k" + i), bytes("v" + i), false,
                    "t" + i, "r1"));
        }
        io.tieringkv.backup.pitr.ArchiveLifecycleManager manager =
                new io.tieringkv.backup.pitr.ArchiveLifecycleManager(
                        archive,
                        new io.tieringkv.backup.pitr.RetentionPolicy(
                                1, 0, -1));
        manager.cleanup();
        assertThat(manager.segmentCount()).isLessThanOrEqualTo(1);
    }

    @ParameterizedTest(name = "groups {0}")
    @ValueSource(ints = {1, 3, 10})
    void cdcFanOutGroupCounts(int groups) throws Exception {
        Path logDir = dir.resolve("fan-" + groups);
        CDCProducer producer = new CDCProducer(logDir);
        for (int i = 0; i < 10; i++) {
            producer.emit(ChangeEvent.EventType.PUT, bytes("k" + i),
                    bytes("v" + i), false, "t" + i, "r1");
        }
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("fan-ckpt-" + groups));
        for (int g = 0; g < groups; g++) {
            registry.register("g" + g).consume(event -> {
            });
        }
        assertThat(registry.size()).isEqualTo(groups);
        for (int g = 0; g < groups; g++) {
            assertThat(registry.group("g" + g).checkpoint())
                    .isEqualTo(9);
        }
    }

    @Test
    void cdcGroupDuplicateRegisterShared() throws Exception {
        Path logDir = dir.resolve("dup-group");
        new CDCProducer(logDir);
        CDCConsumerRegistry registry = new CDCConsumerRegistry(
                logDir, dir.resolve("dup-ckpt"));
        registry.register("g1");
        assertThat(registry.register("g1")).isSameAs(
                registry.group("g1"));
    }

    @Test
    void expiredTokenCommandDenied() throws Exception {
        CredentialManager credentials = new CredentialManager();
        String token = credentials.issue(Role.ADMIN, 1);
        GatewayAuthSession session = new GatewayAuthSession(credentials);
        session.authenticate(token);
        Thread.sleep(5);
        assertThatThrownBy(() -> CommandPermissionGuard.require(
                session.role(), "INFO"))
                .isInstanceOf(SecurityException.class);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
