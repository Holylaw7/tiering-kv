package io.tieringkv.command;

import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.lifecycle.ShutdownManager;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class Stage2CommandHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void scanSupportsCursorPaginationAndMatchPattern() {
        MemTable memTable = MemTable.create();
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), memTable);
        for (int i = 0; i < 25; i++) {
            engine.execute(cmd("SET", "scan:" + String.format("%02d", i), "v" + i));
        }

        Set<String> collected = new HashSet<>();
        String cursor = "0";
        int iterations = 0;
        do {
            RespValue res = engine.execute(cmd("SCAN", cursor, "MATCH", "scan:*", "COUNT", "10"));
            assertThat(res).isInstanceOf(RespArray.class);
            RespArray pair = (RespArray) res;
            cursor = text((RespBulkString) pair.values().get(0));
            RespArray keys = (RespArray) pair.values().get(1);
            for (RespValue item : keys.values()) {
                collected.add(text((RespBulkString) item));
            }
            iterations++;
        } while (!"0".equals(cursor) && iterations < 10);

        assertThat(cursor).isEqualTo("0");
        assertThat(iterations).isGreaterThan(1);
        assertThat(collected).hasSize(25);
    }

    @Test
    void hscanPaginatesDeterministicallyAndHdelDeletesEmptyHashKey() {
        MemTable memTable = MemTable.create();
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), memTable);
        engine.execute(cmd("HSET", "h:1", "f1", "v1", "f2", "v2", "f3", "v3"));

        List<String> fields = new ArrayList<>();
        String cursor = "0";
        int pages = 0;
        do {
            RespArray pair = (RespArray) engine.execute(cmd("HSCAN", "h:1", cursor, "COUNT", "2"));
            cursor = text((RespBulkString) pair.values().get(0));
            RespArray items = (RespArray) pair.values().get(1);
            for (int i = 0; i < items.values().size(); i += 2) {
                fields.add(text((RespBulkString) items.values().get(i)));
            }
            pages++;
        } while (!"0".equals(cursor) && pages < 5);

        assertThat(pages).isEqualTo(2);
        assertThat(fields).containsExactly("f1", "f2", "f3");

        RespValue delCount = engine.execute(cmd("HDEL", "h:1", "f1", "f2", "f3"));
        assertThat(((RespInteger) delCount).value()).isEqualTo(3L);
        assertThat(memTable.exists(bytes("h:1"))).isFalse();
        assertThat(((RespSimpleString) engine.execute(cmd("TYPE", "h:1"))).value()).isEqualTo("none");
    }

    @Test
    void execRollbackMatchesBinaryEqualKeysAndPreservesTtl() {
        MemTable memTable = MemTable.create();
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), memTable);
        engine.execute(cmd("PSETEX", "tx:key", "10000", "initial"));

        ConnectionContext.enter();
        try {
            engine.execute(cmd("MULTI"));
            // Two distinct byte[] instances for the same key "tx:key"
            engine.execute(new RespCommand("set", List.of(
                    "tx:key".getBytes(StandardCharsets.UTF_8), bytes("mutated"))));
            engine.execute(new RespCommand("incr", List.of(
                    "tx:key".getBytes(StandardCharsets.UTF_8))));
            RespValue execResult = engine.execute(cmd("EXEC"));
            assertThat(execResult).isInstanceOf(RespArray.class);
            RespArray arr = (RespArray) execResult;
            assertThat(arr.values()).hasSize(2);
            assertThat(arr.values().get(1)).isInstanceOf(RespError.class);
        } finally {
            ConnectionContext.exit();
        }

        assertThat(memTable.get(bytes("tx:key"))).isEqualTo(bytes("initial"));
        assertThat(memTable.ttlMillis(bytes("tx:key"))).isGreaterThan(0L);
    }

    @Test
    void hotKeyCacheEvictsBeyondLimitAndClearsOnClear() {
        MemTable memTable = MemTable.create();
        HotKeyPolicy policy = new HotKeyPolicy(1000, 1, 2, 5000);
        HotKeyReadCache cache = new HotKeyReadCache(new HotKeyDetector(policy), policy, memTable);
        HotKeyStorageEngine hotStorage = new HotKeyStorageEngine(memTable, cache);

        hotStorage.put(bytes("hk1"), bytes("v1"));
        hotStorage.put(bytes("hk2"), bytes("v2"));
        hotStorage.put(bytes("hk3"), bytes("v3"));
        for (int i = 0; i < 3; i++) {
            assertThat(hotStorage.get(bytes("hk1"))).isEqualTo(bytes("v1"));
            assertThat(hotStorage.get(bytes("hk2"))).isEqualTo(bytes("v2"));
            assertThat(hotStorage.get(bytes("hk3"))).isEqualTo(bytes("v3"));
        }

        hotStorage.clear();
        assertThat(hotStorage.get(bytes("hk1"))).isNull();
        assertThat(hotStorage.get(bytes("hk2"))).isNull();
        assertThat(hotStorage.get(bytes("hk3"))).isNull();
    }

    @Test
    void shutdownManagerClosesExtraResourcesIdempotently() throws Exception {
        MemTable memTable = MemTable.create();
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), memTable);
        MetricsRegistry metrics = new MetricsRegistry();
        AtomicInteger extraClosed = new AtomicInteger();

        try (WALManager wal = new WALManager(new WALConfig(tempDir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO));
             TieringKvServer server = new TieringKvServer(new ServerConfig("127.0.0.1", 0), engine, metrics)) {
            server.start();
            ShutdownManager manager = new ShutdownManager(
                    server, metrics, wal, memTable, 200L,
                    extraClosed::incrementAndGet);
            manager.shutdown();
            manager.shutdown();
            assertThat(extraClosed.get()).isEqualTo(1);
        }
    }

    private static RespCommand cmd(String name, String... args) {
        List<byte[]> argBytes = new ArrayList<>(args.length);
        for (String arg : args) {
            argBytes.add(bytes(arg));
        }
        return new RespCommand(name.toLowerCase(), argBytes);
    }

    private static byte[] bytes(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private static String text(RespBulkString value) {
        return new String(value.bytes(), StandardCharsets.UTF_8);
    }
}
