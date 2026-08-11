package io.tieringkv.benchmark.mvcc;

import io.tieringkv.monitor.CapacityPlanner;
import io.tieringkv.saas.billing.BillingPeriod;
import io.tieringkv.saas.billing.Invoice;
import io.tieringkv.saas.billing.InvoiceExporter;
import io.tieringkv.sharding.ShardMigration;
import io.tieringkv.sharding.ShardRouter;
import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 30 基准（进程内口径，如实记录）。 */
class Phase30BenchmarkTest {

    @ParameterizedTest(name = "routes {0}")
    @ValueSource(ints = {1_000, 10_000})
    void shardRouteThroughput(int count) {
        ShardRouter router = new ShardRouter(16);
        byte[] key = bytes("user:1");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            router.route(key);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE30-BENCH ROUTE %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {1_000, 10_000})
    void shardMigrationThroughput(int count) {
        Map<String, byte[]> source = new LinkedHashMap<>();
        Map<String, byte[]> target = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ShardMigration migration = new ShardMigration(source, target);
        long start = System.nanoTime();
        migration.migrate(key -> true);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE30-BENCH MIGRATE %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(source).isEmpty();
    }

    @ParameterizedTest(name = "writes {0}")
    @ValueSource(ints = {100, 1000})
    void sqlTxnThroughput(int writes) {
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", ops -> {
                });
        long start = System.nanoTime();
        for (int i = 0; i < writes; i++) {
            executor.execute(new SqlTxnParser().parse("BEGIN"));
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v" + i + "'"));
            executor.execute(new SqlTxnParser().parse("COMMIT"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE30-BENCH SQL-TXN %d -> %d txn/s%n",
                writes, writes * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "invoices {0}")
    @ValueSource(ints = {100, 1000})
    void billingExportThroughput(int count) {
        Invoice invoice = new Invoice("t1", "p",
                new BillingPeriod(0, 1, true),
                java.util.List.of(new Invoice.LineItem(
                        io.tieringkv.saas.UsageMeter.MeterType.REQUESTS,
                        100, 0.01, 1.0)));
        InvoiceExporter exporter = new InvoiceExporter();
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            exporter.toCsv(invoice);
            exporter.toJson(invoice);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE30-BENCH INVOICE %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @Test
    void capacityPlanLatency() {
        CapacityPlanner planner = new CapacityPlanner();
        long start = System.nanoTime();
        for (int i = 0; i < 10_000; i++) {
            planner.estimate(8, 1000, 1_000_000, 100, 100_000);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE30-BENCH CAPACITY %d ms%n", elapsedMs);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
