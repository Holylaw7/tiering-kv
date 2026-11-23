package io.tieringkv.storage.wal;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class WALStorageEngineTest {

    @TempDir
    Path dir;

    private MemTable newMemTable() {
        return MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
    }

    @Test
    void writesSurviveCrashThroughRecovery() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            StorageEngine storage = new WALStorageEngine(wal, memTable);
            storage.put("k".getBytes(), "v".getBytes(), 60_000);
            storage.put("del".getBytes(), "x".getBytes());
            storage.delete("del".getBytes());
        }

        MemTable recovered = newMemTable();
        new RecoveryManager(config).recover(recovered);
        assertThat(recovered.get("k".getBytes())).isEqualTo("v".getBytes());
        assertThat(recovered.get("del".getBytes())).isNull();
    }

    @Test
    void appendAfterCloseThrowsWalWriteException() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        WALManager wal = new WALManager(config);
        StorageEngine storage = new WALStorageEngine(wal, newMemTable());
        wal.close();
        assertThatThrownBy(() -> storage.put("k".getBytes(), "v".getBytes()))
                .isInstanceOf(WalWriteException.class);
    }

    @Test
    void commandLayerIsUnawareOfWal() throws Exception {
        WALConfig config = new WALConfig(dir, 1024 * 1024, WALConfig.FsyncPolicy.NO);
        MemTable memTable = newMemTable();
        try (WALManager wal = new WALManager(config)) {
            CommandEngine engine = new CommandEngine(
                    CommandRegistry.createDefault(), new WALStorageEngine(wal, memTable));
            assertThat(execute(engine, "set", "key", "value"))
                    .isEqualTo(new RespSimpleString("OK"));
            assertThat(execute(engine, "get", "key"))
                    .isEqualTo(new RespBulkString("value".getBytes(StandardCharsets.UTF_8)));
            assertThat(execute(engine, "del", "key")).isInstanceOf(io.tieringkv.protocol.RespInteger.class);
            assertThat(execute(engine, "get", "key")).isEqualTo(RespNull.BULK_STRING);
        }
    }

    private static io.tieringkv.protocol.RespValue execute(CommandEngine engine, String name, String... args) {
        List<byte[]> argBytes = new ArrayList<>(args.length);
        for (String arg : args) {
            argBytes.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return engine.execute(new io.tieringkv.command.RespCommand(name, argBytes));
    }
}
