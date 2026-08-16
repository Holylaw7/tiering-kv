package io.tieringkv.storage;

import io.tieringkv.cache.block.BlockCache;
import io.tieringkv.cache.block.CachePolicy;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.memory.MemoryPool;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.cache.EvictionManager;
import io.tieringkv.storage.cache.HotnessTracker;
import io.tieringkv.storage.cache.LFUPolicy;
import io.tieringkv.storage.cache.TrackingStorageEngine;
import io.tieringkv.storage.cold.ColdMigration;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.io.IOStatistics;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.types.MultiModelCodec;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.tiering.WatermarkManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import io.tieringkv.vector.indexfile.VectorIndexStore;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 生产链原子字符串操作回归（ADR-0351）。
 *
 * <p>复现实践运行缺陷：Main.java 生产链最外层装饰器此前只实现
 * StorageEngine，命令层 {@code instanceof AtomicStringOps} 失败，
 * SETEX 后 TTL 恒为 -1，INCR/APPEND 退化为非原子 get+put。
 * 本测试按 Main 相同装配（Tracking → Tiering → HotKey → WAL →
 * MemTable，外覆 VectorIndexSync）验证原子语义与 TTL 透传。
 */
class ProductionChainAtomicOpsTest {

    @TempDir
    Path dir;

    private CommandEngine engine;
    private VectorIndexStore vectorStore;

    @Test
    void setexThenTtlAndPttlReturnRemaining() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertOk(fixture.engine().execute(
                    new RespCommand("setex", List.of(
                            bytes("k"), bytes("100"), bytes("v")))));
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("k"))))))
                    .isBetween(99L, 100L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("pttl", List.of(bytes("k"))))))
                    .isBetween(99_000L, 100_000L);
        }
    }

    @Test
    void expireThenPersistRoundTrip() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertOk(fixture.engine().execute(
                    new RespCommand("set", List.of(bytes("k"), bytes("v")))));
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("expire", List.of(
                            bytes("k"), bytes("100"))))))
                    .isEqualTo(1L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("k"))))))
                    .isBetween(99L, 100L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("persist", List.of(bytes("k"))))))
                    .isEqualTo(1L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("k"))))))
                    .isEqualTo(-1L);
        }
    }

    @Test
    void incrIsAtomicRetainsTtlAndInvalidatesHotCache() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertOk(fixture.engine().execute(
                    new RespCommand("setex", List.of(
                            bytes("hot"), bytes("100"), bytes("5")))));
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("incr", List.of(bytes("hot"))))))
                    .isEqualTo(6L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("hot"))))))
                    .isBetween(99L, 100L);
            assertThat(bulk(fixture.engine().execute(
                    new RespCommand("get", List.of(bytes("hot"))))))
                    .isEqualTo(bytes("6"));

            // 热点化后原子写必须失效热缓存（防止旧值命中）
            for (int i = 0; i < 50; i++) {
                fixture.engine().execute(
                        new RespCommand("get", List.of(bytes("hot"))));
            }
            assertOk(fixture.engine().execute(
                    new RespCommand("setex", List.of(
                            bytes("hot"), bytes("100"), bytes("42")))));
            assertThat(bulk(fixture.engine().execute(
                    new RespCommand("get", List.of(bytes("hot"))))))
                    .isEqualTo(bytes("42"));
        }
    }

    @Test
    void appendRetainsTtl() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertOk(fixture.engine().execute(
                    new RespCommand("setex", List.of(
                            bytes("k"), bytes("100"), bytes("ab")))));
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("append", List.of(
                            bytes("k"), bytes("cd"))))))
                    .isEqualTo(4L);
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("k"))))))
                    .isBetween(99L, 100L);
            assertThat(bulk(fixture.engine().execute(
                    new RespCommand("get", List.of(bytes("k"))))))
                    .isEqualTo(bytes("abcd"));
        }
    }

    @Test
    void getsetReturnsOldAndClearsTtl() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertOk(fixture.engine().execute(
                    new RespCommand("setex", List.of(
                            bytes("k"), bytes("100"), bytes("v")))));
            assertThat(bulk(fixture.engine().execute(
                    new RespCommand("getset", List.of(
                            bytes("k"), bytes("v2"))))))
                    .isEqualTo(bytes("v"));
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("k"))))))
                    .isEqualTo(-1L);
            assertThat(bulk(fixture.engine().execute(
                    new RespCommand("get", List.of(bytes("k"))))))
                    .isEqualTo(bytes("v2"));
        }
    }

    @Test
    void missingKeyTtlIsMinusTwo() throws Exception {
        try (Fixture fixture = newFixture()) {
            assertThat(integer(fixture.engine().execute(
                    new RespCommand("ttl", List.of(bytes("nope"))))))
                    .isEqualTo(-2L);
        }
    }

    @Test
    void vectorSyncStillWorksThroughAtomicChain() throws Exception {
        try (Fixture fixture = newFixture()) {
            byte[] vector = vectorValue(1.0f, 2.0f, 3.0f);
            assertOk(fixture.engine().execute(
                    new RespCommand("set", List.of(bytes("vec"), vector))));
            assertThat(fixture.vectorStore().size()).isEqualTo(1);
            fixture.engine().execute(
                    new RespCommand("del", List.of(bytes("vec"))));
            assertThat(fixture.vectorStore().size()).isZero();
        }
    }

    // ---------- helpers ----------

    private Fixture newFixture() throws Exception {
        Path base = dir.resolve("chain-" + System.nanoTime());
        Path walDir = base.resolve("wal");
        Path coldDir = base.resolve("cold");
        Path migrationDir = base.resolve("migration");

        WALConfig walConfig = WALConfig.defaults(walDir);
        MemoryManager memory = new MemoryManager(1L << 30);
        MemTable memTable = MemTable.create(memory);
        WALManager wal = new WALManager(walConfig);
        wal.recover(memTable);

        MemoryPool pool = new MemoryPool();
        BlockCache blockCache = new BlockCache(CachePolicy.defaults(), pool);
        IOStatistics ioStats = new IOStatistics();
        ColdStorageEngine cold = new ColdStorageEngine(
                new ColdStorageEngine.Config(
                        coldDir, 4096, 10, 1 << 20, 8),
                blockCache, ioStats, true);
        TieringController tiering = new TieringController(
                new TieringController.Config(
                        WatermarkManager.Config.defaults(), 1, 2, 3, 0,
                        5000, migrationDir),
                memory, memTable, wal, cold);
        tiering.recover();
        EvictionManager eviction = new EvictionManager(
                memTable, memory, new LFUPolicy(new HotnessTracker(1000)),
                new ColdMigration(cold), wal, tiering.migrationScheduler(),
                System::currentTimeMillis, 64);
        WALStorageEngine walStorage = new WALStorageEngine(wal, memTable);
        HotKeyReadCache hotCache = new HotKeyReadCache(
                new HotKeyDetector(HotKeyPolicy.defaults()),
                HotKeyPolicy.defaults(), walStorage);
        StorageEngine storage = new TrackingStorageEngine(
                new TieringStorageEngine(
                        new HotKeyStorageEngine(walStorage, hotCache), tiering),
                eviction);
        VectorIndexStore vectorStore = new VectorIndexStore(6);
        StorageEngine syncStorage = new VectorIndexSyncStorageEngine(
                storage, vectorStore);
        CommandEngine engine = new CommandEngine(
                CommandRegistry.createDefault(), syncStorage);
        return new Fixture(engine, vectorStore, wal, cold, tiering);
    }

    private static byte[] vectorValue(float... components) {
        return MultiModelCodec.encodeVector(components);
    }

    private static void assertOk(RespValue value) {
        assertThat(value).isInstanceOf(RespSimpleString.class);
        assertThat(((RespSimpleString) value).value()).isEqualTo("OK");
    }

    private static long integer(RespValue value) {
        assertThat(value).isInstanceOf(RespInteger.class);
        return ((RespInteger) value).value();
    }

    private static byte[] bulk(RespValue value) {
        assertThat(value).isInstanceOf(RespBulkString.class);
        return ((RespBulkString) value).bytes();
    }

    private static byte[] bytes(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    private record Fixture(
            CommandEngine engine,
            VectorIndexStore vectorStore,
            WALManager wal,
            ColdStorageEngine cold,
            TieringController tiering) implements AutoCloseable {

        @Override
        public void close() throws Exception {
            try {
                tiering.close();
            } finally {
                try {
                    cold.close();
                } finally {
                    wal.close();
                }
            }
        }
    }
}
