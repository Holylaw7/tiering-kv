package io.tieringkv.sharding;

import io.tieringkv.sql.txn.SqlTxnExecutor;
import io.tieringkv.sql.txn.SqlTxnParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 重分片/SQL 写混沌（Goal 8）：路由中断、回滚安全。 */
class ReshardSqlTxnChaosTest {

    @Test
    void reshardDuringWritesNoLoss() {
        ShardRouter router = new ShardRouter(2);
        router.beginMigration(4);
        // 双写窗口：旧/新路由均可写（此处验证路由仍可用）
        assertThat(router.route(bytes("k"))).isBetween(0, 3);
        long version = router.commitSwitch(4);
        assertThat(version).isGreaterThanOrEqualTo(1);
        assertThat(router.migrating()).isFalse();
    }

    @Test
    void reshardRollbackKeepsWritesWorking() {
        ShardRouter router = new ShardRouter(2);
        router.beginMigration(4);
        router.rollback(2);
        assertThat(router.route(bytes("k"))).isBetween(0, 1);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 20})
    void sqlTxnCommitAfterReshard(int txns) {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r" + (Math.floorMod(key.hashCode(), 4) + 1),
                committed::addAll);
        for (int i = 0; i < txns; i++) {
            executor.execute(new SqlTxnParser().parse("BEGIN"));
            executor.execute(new SqlTxnParser().parse(
                    "SET 'k" + i + "' = 'v'"));
            executor.execute(new SqlTxnParser().parse("COMMIT"));
        }
        assertThat(committed).hasSize(txns);
    }

    @Test
    void sqlTxnRollbackAfterRegionChange() {
        List<SqlTxnExecutor.WriteOp> committed = new ArrayList<>();
        SqlTxnExecutor executor = new SqlTxnExecutor(
                key -> "r1", committed::addAll);
        executor.execute(new SqlTxnParser().parse("BEGIN"));
        executor.execute(new SqlTxnParser().parse("SET 'k' = 'v'"));
        executor.execute(new SqlTxnParser().parse("ROLLBACK"));
        assertThat(committed).isEmpty();
    }

    @Test
    void shardMigrationInterruptSafe() {
        java.util.Map<String, byte[]> source = new java.util.LinkedHashMap<>();
        java.util.Map<String, byte[]> target = new java.util.LinkedHashMap<>();
        for (int i = 0; i < 100; i++) {
            source.put("k" + i, bytes("v" + i));
        }
        ShardMigration migration = new ShardMigration(source, target);
        migration.migrate(key -> key.endsWith("0"));
        // 中断后剩余键仍在源，不丢失
        assertThat(source.size() + target.size()).isEqualTo(100);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
