package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.HybridLogicalClock;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.PrewriteExecutor;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.TransactionCoordinator;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis 自动事务集成（ADR-0079）：SET→GET、SET→DEL、并发冲突、失败原子性。 */
class RedisMvccIntegrationTest {

    @Test
    void setThenGetRoundTrip() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("set",
                List.of(key("user:1"), bytes("tom"))))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue response = fixture.gateway.execute("get", List.of(key("user:1")));
        assertThat(response).isInstanceOf(RespBulkString.class);
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("tom"));
    }

    @Test
    void setOverwritesLatestValue() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v1")));
        fixture.gateway.execute("set", List.of(key("k"), bytes("v2")));
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v2"));
        assertThat(fixture.mvcc.versions(key("k"))).hasSize(2);
        assertThat(fixture.mvcc.versions(key("k")).get(1).writeType())
                .isEqualTo(WriteType.PUT);
    }

    @Test
    void deleteRemovesKey() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        RespValue response = fixture.gateway.execute("del", List.of(key("k")));
        assertThat(response).isEqualTo(new RespInteger(1));
        assertThat(fixture.gateway.execute("get", List.of(key("k"))))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void deleteMissingKeyReturnsZero() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("del", List.of(key("missing"))))
                .isEqualTo(new RespInteger(0));
    }

    @Test
    void msetThenMget() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("mset", List.of(
                key("a"), bytes("1"), key("b"), bytes("2"))))
                .isEqualTo(new RespSimpleString("OK"));
        RespValue response = fixture.gateway.execute("mget",
                List.of(key("a"), key("b"), key("missing")));
        List<RespValue> values = ((io.tieringkv.protocol.RespArray) response).values();
        assertThat(((RespBulkString) values.get(0)).bytes()).isEqualTo(bytes("1"));
        assertThat(((RespBulkString) values.get(1)).bytes()).isEqualTo(bytes("2"));
        assertThat(values.get(2)).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void provisionalLockNotVisibleToGet() {
        Fixture fixture = singleShard();
        new PrewriteExecutor().prewrite(fixture.mvcc, fixture.locks,
                key("k"), bytes("uncommitted"), false,
                "txn-pending", key("k"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        assertThat(fixture.gateway.execute("get", List.of(key("k"))))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void concurrentSameKeyNoLostUpdate() throws Exception {
        Fixture fixture = singleShard();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        AtomicInteger conflict = new AtomicInteger();
        AtomicInteger other = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int writer = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    RespValue response = fixture.gateway.execute("set",
                            List.of(key("hot"), bytes("w" + writer)));
                    if (response instanceof RespSimpleString) {
                        ok.incrementAndGet();
                    } else if (response instanceof RespError error
                            && error.message().contains("conflict")) {
                        conflict.incrementAndGet();
                    } else {
                        other.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        // 允许合法串行化（无冲突全部成功），也允许锁冲突部分失败；
        // 不变量：至少一次成功、无未分类响应、最终只有一个最新值、无残留锁
        assertThat(ok.get()).isGreaterThanOrEqualTo(1);
        assertThat(ok.get() + conflict.get()).isEqualTo(threads);
        assertThat(other.get()).isZero();
        assertThat(fixture.locks.size()).isZero();
        RespValue finalValue = fixture.gateway.execute("get", List.of(key("hot")));
        assertThat(finalValue).isInstanceOf(RespBulkString.class);
        byte[] latest = ((RespBulkString) finalValue).bytes();
        boolean matchesWriter = false;
        for (int i = 0; i < threads; i++) {
            if (java.util.Arrays.equals(latest, bytes("w" + i))) {
                matchesWriter = true;
                break;
            }
        }
        assertThat(matchesWriter).isTrue();
    }

    @Test
    void lockConflictReturnsError() {
        Fixture fixture = singleShard();
        new PrewriteExecutor().prewrite(fixture.mvcc, fixture.locks,
                key("k"), bytes("blocked"), false,
                "other-txn", key("k"), 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        RespValue response = fixture.gateway.execute("set",
                List.of(key("k"), bytes("mine")));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message()).contains("conflict");
        assertThat(fixture.gateway.execute("get", List.of(key("k"))))
                .isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void concurrentDifferentKeysAllSucceed() throws Exception {
        Fixture fixture = singleShard();
        int threads = 8;
        CountDownLatch start = new CountDownLatch(1);
        AtomicInteger ok = new AtomicInteger();
        List<Thread> workers = new ArrayList<>();
        for (int i = 0; i < threads; i++) {
            int writer = i;
            Thread thread = new Thread(() -> {
                try {
                    start.await();
                    RespValue response = fixture.gateway.execute("set",
                            List.of(key("k" + writer), bytes("v" + writer)));
                    if (response instanceof RespSimpleString) {
                        ok.incrementAndGet();
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            workers.add(thread);
            thread.start();
        }
        start.countDown();
        for (Thread worker : workers) {
            worker.join(30_000);
        }
        assertThat(ok.get()).isEqualTo(threads);
        for (int i = 0; i < threads; i++) {
            assertThat(fixture.gateway.execute("get", List.of(key("k" + i))))
                    .isInstanceOf(RespBulkString.class);
        }
    }

    @Test
    void transactionLatencyMetricRecorded() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        fixture.gateway.execute("del", List.of(key("k")));
        assertThat(fixture.gatewayMetrics.snapshot().transactionTotal())
                .isEqualTo(2);
        assertThat(fixture.gatewayMetrics.snapshot().transactionLatencyMs())
                .isGreaterThan(0);
    }

    @Test
    void ttlOptionRejectedInTransactionalMode() {
        Fixture fixture = singleShard();
        RespValue response = fixture.gateway.execute("set", List.of(
                key("k"), bytes("v"), bytes("EX"), bytes("10")));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message())
                .contains("not supported with transactional gateway");
    }

    @Test
    void legacyModeWithoutMvccStillWorks() {
        MemTable table = MemTable.create();
        RedisClusterGateway legacy = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", table),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)), "n1");
        assertThat(legacy.execute("set", List.of(key("k"), bytes("v"),
                bytes("EX"), bytes("10")))).isEqualTo(new RespSimpleString("OK"));
        assertThat(table.getEntry(key("k")).expireTimestamp()).isGreaterThan(0);
        legacy.execute("del", List.of(key("k")));
        assertThat(legacy.execute("get", List.of(key("k"))))
                .isEqualTo(RespNull.BULK_STRING);
        table.close();
    }

    @Test
    void multiShardMsetAllOrNothing() {
        Fixture fixture = twoShards();
        assertThat(fixture.gateway.execute("mset", List.of(
                fixture.shard0Key, bytes("a"),
                fixture.shard1Key, bytes("b"))))
                .isEqualTo(new RespSimpleString("OK"));
        assertThat(fixture.mvcc0.latestValue(fixture.shard0Key))
                .isEqualTo(bytes("a"));
        assertThat(fixture.mvcc1.latestValue(fixture.shard1Key))
                .isEqualTo(bytes("b"));
        RespValue response = fixture.gateway.execute("mget",
                List.of(fixture.shard0Key, fixture.shard1Key));
        List<RespValue> values = ((io.tieringkv.protocol.RespArray) response).values();
        assertThat(((RespBulkString) values.get(0)).bytes()).isEqualTo(bytes("a"));
        assertThat(((RespBulkString) values.get(1)).bytes()).isEqualTo(bytes("b"));
    }

    @Test
    void multiShardMsetConflictRollsBackAll() {
        Fixture fixture = twoShards();
        // 第二个 shard 的键已有悬挂锁 → 2PC prewrite 失败 → 全回滚
        new PrewriteExecutor().prewrite(fixture.mvcc1, fixture.locks1,
                fixture.shard1Key, bytes("blocked"), false,
                "other-txn", fixture.shard1Key, 1, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        RespValue response = fixture.gateway.execute("mset", List.of(
                fixture.shard0Key, bytes("a"),
                fixture.shard1Key, bytes("b")));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(fixture.mvcc0.latestValue(fixture.shard0Key)).isNull();
        assertThat(fixture.mvcc1.latestValue(fixture.shard1Key)).isNull();
        assertThat(fixture.locks0.size()).isZero(); // 失败 participant 已回滚
    }

    @Test
    void failoverDuringSetNoPhantomCommit() {
        MvccStorageEngine failing = new MvccStorageEngine(new FailingStorage());
        LockTable locks = new LockTable();
        TimestampOracle oracle = new TimestampOracle();
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(), new TransactionCoordinator(oracle, 60_000),
                ignored -> new AutoTransactionExecutor.Participant(
                        "r1", failing, locks));
        TransactionCommandHandler handler = new TransactionCommandHandler(
                executor, new GatewayMetricsRegistry());
        RespValue response = handler.mset(List.of(key("a"), bytes("1"),
                key("b"), bytes("2")));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(failing.versionCount()).isZero();
        assertThat(locks.size()).isZero();
    }

    @Test
    void getAfterGcSeesLatest() {
        Fixture fixture = singleShard();
        for (int i = 1; i <= 5; i++) {
            fixture.gateway.execute("set", List.of(key("k"), bytes("v" + i)));
        }
        io.tieringkv.mvcc.gc.BatchGcExecutor gc =
                new io.tieringkv.mvcc.gc.BatchGcExecutor(fixture.mvcc,
                        io.tieringkv.mvcc.gc.GcConfig.DEFAULT);
        gc.updateSafePoint(new io.tieringkv.mvcc.SafePoint(Long.MAX_VALUE / 2));
        gc.gc();
        gc.close();
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v5"));
    }

    @Test
    void deleteThenSetVisible() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v1")));
        fixture.gateway.execute("del", List.of(key("k")));
        fixture.gateway.execute("set", List.of(key("k"), bytes("v2")));
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v2"));
    }

    @Test
    void autoSetCreatesVersionHistory() {
        Fixture fixture = singleShard();
        for (int i = 1; i <= 4; i++) {
            fixture.gateway.execute("set", List.of(key("k"), bytes("v" + i)));
        }
        assertThat(fixture.mvcc.versions(key("k"))).hasSize(4);
        assertThat(new SnapshotReader().get(fixture.mvcc, key("k"),
                Long.MAX_VALUE)).isEqualTo(bytes("v4"));
    }

    @Test
    void msetDuplicateKeysLastWins() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("mset", List.of(
                key("k"), bytes("first"), key("k"), bytes("last")));
        RespValue response = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("last"));
    }

    @Test
    void wrongArityRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("set", List.of(key("k"))))
                .isInstanceOf(RespError.class);
        assertThat(fixture.gateway.execute("mset", List.of(key("k"))))
                .isInstanceOf(RespError.class);
    }

    @Test
    void getWrongArityRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("get", List.of()))
                .isInstanceOf(RespError.class);
        fixture.close();
    }

    @Test
    void delWrongArityRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("del", List.of(
                key("a"), key("b")))).isInstanceOf(RespError.class);
        fixture.close();
    }

    @Test
    void mgetEmptyRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("mget", List.of()))
                .isInstanceOf(RespError.class);
        fixture.close();
    }

    @Test
    void msetOddArgsRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("mset", List.of(
                key("a"), bytes("1"), key("b"))))
                .isInstanceOf(RespError.class);
        fixture.close();
    }

    @Test
    void unknownCommandRejected() {
        Fixture fixture = singleShard();
        assertThat(fixture.gateway.execute("hgetall", List.of(key("k"))))
                .isInstanceOf(RespError.class);
        fixture.close();
    }

    @Test
    void movedForRemoteKeyInTransactionalMode() {
        MovedFixture fixture = movedFixture();
        RespValue response = fixture.gateway.execute("get",
                List.of(fixture.remoteKey));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message()).startsWith("MOVED");
        ((MemTable) fixture.mvcc.underlying()).close();
    }

    @Test
    void mgetWithRemoteKeyReturnsMoved() {
        MovedFixture fixture = movedFixture();
        RespValue response = fixture.gateway.execute("mget",
                List.of(fixture.localKey, fixture.remoteKey));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message()).startsWith("MOVED");
        ((MemTable) fixture.mvcc.underlying()).close();
    }

    @Test
    void msetWithRemoteKeyReturnsMoved() {
        MovedFixture fixture = movedFixture();
        RespValue response = fixture.gateway.execute("mset", List.of(
                fixture.localKey, bytes("1"), fixture.remoteKey, bytes("2")));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message()).startsWith("MOVED");
        assertThat(fixture.mvcc.latestValue(fixture.localKey)).isNull();
        ((MemTable) fixture.mvcc.underlying()).close();
    }

    @Test
    void getAfterCommitVisibleImmediately() {
        Fixture fixture = singleShard();
        RespValue set = fixture.gateway.execute("set",
                List.of(key("k"), bytes("v")));
        assertThat(set).isEqualTo(new RespSimpleString("OK"));
        RespValue get = fixture.gateway.execute("get", List.of(key("k")));
        assertThat(((RespBulkString) get).bytes()).isEqualTo(bytes("v"));
        fixture.close();
    }

    @Test
    void midTransactionMgetHidesUncommitted() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("a"), bytes("1")));
        // 手动构造 a 的悬挂锁：mget 必须看到 a=null、b=已提交
        new PrewriteExecutor().prewrite(fixture.mvcc, fixture.locks,
                key("a"), bytes("pending"), false,
                "other-txn", key("a"), 4_000_000_000_000_000_000L, 60_000,
                System.currentTimeMillis(), java.util.Set.of());
        fixture.gateway.execute("set", List.of(key("b"), bytes("2")));
        RespValue response = fixture.gateway.execute("mget",
                List.of(key("a"), key("b")));
        List<RespValue> values = ((io.tieringkv.protocol.RespArray) response).values();
        // 悬挂锁只隐藏“未提交的新值”，已提交旧值仍可见（快照读语义）
        assertThat(((RespBulkString) values.get(0)).bytes()).isEqualTo(bytes("1"));
        assertThat(((RespBulkString) values.get(1)).bytes()).isEqualTo(bytes("2"));
        fixture.close();
    }

    @Test
    void legacyInfoWithoutTransactionSections() {
        MemTable table = MemTable.create();
        RedisClusterGateway legacy = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", table),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)), "n1");
        RespValue response = legacy.execute("info", List.of());
        String info = new String(((RespBulkString) response).bytes(),
                StandardCharsets.UTF_8);
        assertThat(info).contains("# Server");
        assertThat(info).doesNotContain("# Transaction");
        assertThat(info).doesNotContain("# MVCC");
        table.close();
    }

    @Test
    void transactionInfoHasLockCount() {
        Fixture fixture = singleShard();
        RespValue response = fixture.gateway.execute("info", List.of());
        String info = new String(((RespBulkString) response).bytes(),
                StandardCharsets.UTF_8);
        assertThat(info).contains("lock_count:0");
        fixture.close();
    }

    @Test
    void deleteThenMgetNull() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        fixture.gateway.execute("del", List.of(key("k")));
        RespValue response = fixture.gateway.execute("mget", List.of(key("k")));
        List<RespValue> values = ((io.tieringkv.protocol.RespArray) response).values();
        assertThat(values.get(0)).isEqualTo(RespNull.BULK_STRING);
        fixture.close();
    }

    @Test
    void locksReleasedAfterCommit() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("set", List.of(key("k"), bytes("v")));
        assertThat(fixture.locks.size()).isZero();
        fixture.gateway.execute("del", List.of(key("k")));
        assertThat(fixture.locks.size()).isZero();
    }

    @Test
    void mgetUsesConsistentSnapshot() {
        Fixture fixture = singleShard();
        fixture.gateway.execute("mset", List.of(
                key("a"), bytes("1"), key("b"), bytes("2")));
        RespValue response = fixture.gateway.execute("mget",
                List.of(key("a"), key("b")));
        List<RespValue> values = ((io.tieringkv.protocol.RespArray) response).values();
        assertThat(((RespBulkString) values.get(0)).bytes()).isEqualTo(bytes("1"));
        assertThat(((RespBulkString) values.get(1)).bytes()).isEqualTo(bytes("2"));
    }

    // ---------- helpers ----------

    private static Fixture singleShard() {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine mvcc = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        GatewayMetricsRegistry metrics = new GatewayMetricsRegistry();
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(), new TransactionCoordinator(oracle, 60_000),
                ignored -> new AutoTransactionExecutor.Participant("r1", mvcc, locks),
                new TransactionMetricsRegistry());
        RedisClusterGateway gateway = new RedisClusterGateway(1,
                Map.of(0, "n1"), Map.of("n1", mvcc.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                "n1", executor, metrics);
        return new Fixture(gateway, mvcc, locks, null, null, null, null,
                null, null, metrics);
    }

    private static Fixture twoShards() {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine mvcc0 = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine mvcc1 = new MvccStorageEngine(MemTable.create());
        LockTable locks0 = new LockTable();
        LockTable locks1 = new LockTable();
        GatewayMetricsRegistry metrics = new GatewayMetricsRegistry();
        AutoTransactionExecutor.Participant p0 =
                new AutoTransactionExecutor.Participant("r0", mvcc0, locks0);
        AutoTransactionExecutor.Participant p1 =
                new AutoTransactionExecutor.Participant("r1", mvcc1, locks1);
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(), new TransactionCoordinator(oracle, 60_000),
                key -> shardOf(key.key()) == 0 ? p0 : p1,
                new TransactionMetricsRegistry());
        RedisClusterGateway gateway = new RedisClusterGateway(2,
                Map.of(0, "n1", 1, "n1"),
                Map.of("n1", mvcc0.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)),
                "n1", executor, metrics);
        byte[] shard0Key = null;
        byte[] shard1Key = null;
        for (int i = 0; i < 10_000 && (shard0Key == null || shard1Key == null); i++) {
            byte[] candidate = key("two:" + i);
            if (shardOf(candidate) == 0 && shard0Key == null) {
                shard0Key = candidate;
            } else if (shardOf(candidate) == 1 && shard1Key == null) {
                shard1Key = candidate;
            }
        }
        return new Fixture(gateway, null, null,
                mvcc0, mvcc1, locks0, locks1, shard0Key, shard1Key, metrics);
    }

    private static int shardOf(byte[] key) {
        int slot = HashSlotRouter.slot(key);
        return Math.min(1, (int) ((long) slot * 2 / HashSlotRouter.SLOT_COUNT));
    }

    /** 两节点网关：shard1 由 n2 持有 → 非本地键返回 MOVED。 */
    private static MovedFixture movedFixture() {
        TimestampOracle oracle = new TimestampOracle();
        MvccStorageEngine mvcc = new MvccStorageEngine(MemTable.create());
        LockTable locks = new LockTable();
        AutoTransactionExecutor executor = new AutoTransactionExecutor(oracle,
                new HybridLogicalClock(), new TransactionCoordinator(oracle, 60_000),
                ignored -> new AutoTransactionExecutor.Participant("r0", mvcc, locks));
        RedisClusterGateway gateway = new RedisClusterGateway(2,
                Map.of(0, "n1", 1, "n2"), Map.of("n1", mvcc.underlying()),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001),
                        "n2", new InetSocketAddress("127.0.0.1", 7002)),
                "n1", executor, new GatewayMetricsRegistry());
        byte[] local = null;
        byte[] remote = null;
        for (int i = 0; i < 10_000 && (local == null || remote == null); i++) {
            byte[] candidate = key("moved:" + i);
            if (shardOf(candidate) == 0 && local == null) {
                local = candidate;
            } else if (shardOf(candidate) == 1 && remote == null) {
                remote = candidate;
            }
        }
        return new MovedFixture(gateway, mvcc, local, remote);
    }

    private static byte[] key(String value) {
        return bytes("gw:" + value);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(RedisClusterGateway gateway, MvccStorageEngine mvcc,
                           LockTable locks, MvccStorageEngine mvcc0,
                           MvccStorageEngine mvcc1, LockTable locks0,
                           LockTable locks1, byte[] shard0Key,
                           byte[] shard1Key, GatewayMetricsRegistry gatewayMetrics)
            implements AutoCloseable {
        @Override
        public void close() {
            if (mvcc != null) {
                ((MemTable) mvcc.underlying()).close();
            }
            if (mvcc0 != null) {
                ((MemTable) mvcc0.underlying()).close();
            }
            if (mvcc1 != null) {
                ((MemTable) mvcc1.underlying()).close();
            }
        }
    }

    private record MovedFixture(RedisClusterGateway gateway,
                                MvccStorageEngine mvcc, byte[] localKey,
                                byte[] remoteKey) {
    }

    /** 始终失败的底层存储：模拟 leader 故障导致 prewrite 失败。 */
    private static final class FailingStorage implements StorageEngine {
        @Override
        public void put(byte[] key, byte[] value) {
            throw new IllegalStateException("leader down");
        }

        @Override
        public void put(byte[] key, byte[] value, long ttlMillis) {
            throw new IllegalStateException("leader down");
        }

        @Override
        public byte[] get(byte[] key) {
            return null;
        }

        @Override
        public boolean delete(byte[] key) {
            return false;
        }

        @Override
        public boolean exists(byte[] key) {
            return false;
        }

        @Override
        public io.tieringkv.storage.StorageIterator iterator() {
            return new io.tieringkv.storage.StorageIterator() {
                @Override
                public boolean hasNext() {
                    return false;
                }

                @Override
                public io.tieringkv.storage.memory.KeyValueEntry next() {
                    throw new IllegalStateException("empty");
                }

                @Override
                public void close() {
                }
            };
        }

        @Override
        public long size() {
            return 0;
        }
    }
}
