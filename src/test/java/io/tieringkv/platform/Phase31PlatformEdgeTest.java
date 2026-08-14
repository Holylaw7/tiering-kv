package io.tieringkv.platform;

import io.tieringkv.console.ConsoleApi;
import io.tieringkv.deploy.multicloud.CloudMigration;
import io.tieringkv.deploy.multicloud.MulticloudConfig;
import io.tieringkv.monitor.AlertManager;
import io.tieringkv.monitor.Phase28Metrics;
import io.tieringkv.saas.ClusterTenant;
import io.tieringkv.saas.TenantAuditLog;
import io.tieringkv.saas.TenantRegistry;
import io.tieringkv.saas.billing.BillingScheduler;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.BillingPlan;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Role;
import io.tieringkv.sql.txn.SqlTxn2PcBridge;
import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import io.tieringkv.vector.Embedding;
import io.tieringkv.vector.VectorStore;
import io.tieringkv.vector.cluster.VectorDoubleWriteRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 31 平台边缘：SQL2PC/双写/账单/多云/控制台参数矩阵。 */
class Phase31PlatformEdgeTest {

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void sql2pcVolumeMatrix(int writes) {
        List<io.tieringkv.transaction.rpc.TxnMessages.Mutation> received =
                new ArrayList<>();
        SqlTxn2PcBridge bridge = new SqlTxn2PcBridge(
                mutations -> {
                    received.addAll(mutations);
                    return true;
                });
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> bridge.commit(ops));
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        for (int i = 0; i < writes; i++) {
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
        }
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(received).hasSize(writes);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void doubleWriteVolumeMatrix(int count) {
        VectorStore primary = new VectorStore();
        VectorStore secondary = new VectorStore();
        VectorDoubleWriteRouter router =
                new VectorDoubleWriteRouter(primary, secondary);
        router.beginMigration();
        for (int i = 0; i < count; i++) {
            router.put(new Embedding("e" + i,
                    new float[]{i % 3, 3 - i % 3}));
        }
        assertThat(router.primarySize()).isEqualTo(count);
        assertThat(router.secondarySize()).isEqualTo(count);
    }

    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 100, 250, 500,
            1000, 2500, 5000, 10000, 25000, 50000})
    void billingRollQuantityMatrix(long quantity) {
        TenantAuditLog audit = new TenantAuditLog();
        BillingScheduler scheduler = new BillingScheduler(100, audit);
        scheduler.meter("t1").record(
                UsageMeter.MeterType.REQUESTS, quantity);
        Invoice invoice = scheduler.roll("t1",
                new BillingPlan("p", Map.of(
                        UsageMeter.MeterType.REQUESTS, 0.01)), 0);
        assertThat(invoice.total()).isEqualTo(quantity * 0.01);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void cloudMigrationVolumeMatrix(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        CloudMigration migration = new CloudMigration(source, target);
        migration.migrate();
        assertThat(migration.verify()).isTrue();
    }

    @ParameterizedTest(name = "tenants {0}")
    @ValueSource(ints = {1, 5, 10, 25, 50, 100, 200, 500, 1000,
            2000, 5000, 10000, 20000, 50000, 100000})
    void consoleTenantVolume(int count) {
        CredentialManager credentials = new CredentialManager();
        ConsoleApi api = new ConsoleApi(new TenantRegistry(),
                new Phase28Metrics(), new AlertManager(List.of()),
                credentials);
        String token = credentials.issue(Role.ADMIN, 60_000);
        for (int i = 0; i < count; i++) {
            api.createTenant(token,
                    new ClusterTenant("t" + i, "c" + i, 3, 10));
        }
        assertThat(api.listTenants(token)).hasSize(count);
    }

    @Test
    void multicloudConfigMatrix() {
        MulticloudConfig config = new MulticloudConfig(
                "gp2", "nginx", "ghcr.io/holylaw7/tiering-kv", 3);
        assertThat(config.gatewayReplicas()).isEqualTo(3);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
