package io.tieringkv.storage.cache;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** 命令层 + 热度跟踪 + 淘汰的端到端集成（Phase 3）。 */
class CacheIntegrationTest {

    @Test
    void commandLayerWorksWithEviction() {
        MutableClock clock = new MutableClock(0);
        MemoryManager memoryManager = new MemoryManager(300);
        MemTable memTable = MemTable.createForTest(clock, memoryManager);
        CountingMigration migration = new CountingMigration();
        EvictionManager evictionManager = new EvictionManager(
                memTable, memoryManager, new LFUPolicy(new HotnessTracker(1000)),
                migration, clock, 64);
        TrackingStorageEngine storage = new TrackingStorageEngine(memTable, evictionManager, clock);
        CommandEngine engine = new CommandEngine(CommandRegistry.createDefault(), storage);

        assertThat(execute(engine, "ping")).isEqualTo(new RespSimpleString("PONG"));

        assertThat(execute(engine, "set", "hot", "v"))
                .isEqualTo(new RespSimpleString("OK"));
        for (int i = 0; i < 5; i++) {
            execute(engine, "get", "hot");
        }
        for (int i = 1; i < 8; i++) {
            execute(engine, "set", "k" + i, "x".repeat(32));
        }

        assertThat(execute(engine, "get", "hot"))
                .isEqualTo(new RespBulkString("v".getBytes(StandardCharsets.UTF_8)));
        assertThat(migration.count()).isGreaterThanOrEqualTo(1);

        String evictedKey = migration.migrated.get(0).key();
        assertThat(execute(engine, "get", evictedKey)).isEqualTo(RespNull.BULK_STRING);
    }

    private static io.tieringkv.protocol.RespValue execute(CommandEngine engine, String name, String... args) {
        List<byte[]> argBytes = new ArrayList<>(args.length);
        for (String arg : args) {
            argBytes.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return engine.execute(new io.tieringkv.command.RespCommand(name, argBytes));
    }

    private static final class CountingMigration implements MigrationCallback {
        private final List<KeyValueEntryView> migrated = new ArrayList<>();

        @Override
        public void migrate(io.tieringkv.storage.memory.KeyValueEntry entry) {
            migrated.add(new KeyValueEntryView(new String(entry.key(), StandardCharsets.UTF_8)));
        }

        private int count() {
            return migrated.size();
        }

        private record KeyValueEntryView(String key) {
        }
    }
}
