package io.tieringkv.platform;

import io.tieringkv.dr.ConsistencyMode;
import io.tieringkv.dr.GlobalReadRouter;
import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.saas.billing.BillingPeriod;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.billing.InvoiceExporter;
import io.tieringkv.saas.UsageMeter;
import io.tieringkv.sharding.ShardMigration;
import io.tieringkv.sharding.ShardRouter;
import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 30 最终边缘矩阵：重分片/SQL/水位/账单/容量参数化。 */
class Phase30FinalEdgeTest {

    @ParameterizedTest(name = "key {0}")
    @ValueSource(strings = {"a", "b", "c", "d", "e", "f", "g", "h",
            "i", "j", "k", "l", "m", "n", "o"})
    void shardRouterKeys(String key) {
        ShardRouter router = new ShardRouter(8);
        assertThat(router.route(bytes(key))).isBetween(0, 7);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100, 200, 500,
            1000, 2000, 5000, 10000, 20000, 50000})
    void shardMigrationVolumeMatrix(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v"));
        }
        ShardMigration migration = new ShardMigration(source, target);
        assertThat(migration.migrate(key -> true)).isEqualTo(count);
        assertThat(target).hasSize(count);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1, 2, 5, 10, 20, 50, 100, 200, 500,
            1000, 2000, 5000, 10000, 20000, 50000})
    void sqlTxnOpsMatrix(int ops) {
        List<io.tieringkv.sql.txn.SqlTxnExecutor.WriteOp> committed =
                new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        for (int i = 0; i < ops; i++) {
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
        }
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        assertThat(committed).hasSize(ops);
    }

    @ParameterizedTest(name = "lag {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 100, 250, 500,
            1000, 2500, 5000, 10000, 25000, 50000})
    void globalReadLagMatrix(long lag) {
        AtomicLong replicated = new AtomicLong(1_000_000);
        GlobalReadRouter router = new GlobalReadRouter(
                replicated::get, region -> 1_000_000 - lag,
                ConsistencyMode.BOUNDED);
        assertThat(router.route("a", 1_000_000)).isEqualTo("a");
    }

    @ParameterizedTest(name = "quantity {0}")
    @ValueSource(longs = {0, 1, 5, 10, 25, 50, 100, 250, 500,
            1000, 2500, 5000, 10000, 25000, 50000})
    void invoiceQuantityMatrix(long quantity) {
        Invoice invoice = new Invoice("t", "p",
                new BillingPeriod(0, 1, true),
                List.of(new Invoice.LineItem(
                        UsageMeter.MeterType.REQUESTS,
                        quantity, 0.01, quantity * 0.01)));
        assertThat(invoice.total()).isEqualTo(quantity * 0.01);
        assertThat(new InvoiceExporter().toCsv(invoice))
                .contains("REQUESTS");
    }

    @ParameterizedTest(name = "qps {0}")
    @ValueSource(longs = {1, 10, 100, 1000, 10000, 100000, 1000000,
            10000000, 50000000, 100000000, 250000000, 500000000,
            750000000, 1000000000, 2000000000L})
    void capacityQpsMatrix(long qps) {
        CapacityPlanner.CapacityEstimate estimate =
                new CapacityPlanner().estimate(8, 1000, qps,
                        100, 100_000);
        assertThat(estimate.nodes()).isGreaterThanOrEqualTo(1);
        assertThat(estimate.qps()).isEqualTo(qps);
    }

    @Test
    void allEdgePathsWorkTogether() {
        ShardRouter router = new ShardRouter(2);
        List<io.tieringkv.sql.txn.SqlTxnExecutor.WriteOp> committed =
                new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r" + (router.route(key) + 1),
                committed::addAll);
        router.beginMigration(4);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'k' = 'v'"));
        executor.execute(new SqlTxnParser().parse("COMMIT"));
        router.commitSwitch(4);
        assertThat(committed).hasSize(1);
    }

    @ParameterizedTest(name = "cycle {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void routerCommitCycles(int cycles) {
        ShardRouter router = new ShardRouter(2);
        for (int i = 0; i < cycles; i++) {
            router.beginMigration(4);
            router.commitSwitch(4);
        }
        assertThat(router.routingVersion()).isEqualTo(cycles);
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 2, 3, 4, 5})
    void migrationVerifyVolume(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v" + i));
            target.put("k" + i, bytes("v" + i));
        }
        assertThat(new ShardMigration(source, target).verify())
                .isTrue();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
