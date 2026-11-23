package io.tieringkv.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.concurrency.hotkey.HotKeyDetector;
import io.tieringkv.concurrency.hotkey.HotKeyPolicy;
import io.tieringkv.concurrency.hotkey.HotKeyReadCache;
import io.tieringkv.concurrency.hotkey.HotKeyStorageEngine;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.cold.ColdStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.tiering.TieringController;
import io.tieringkv.storage.tiering.TieringReadStorageEngine;
import io.tieringkv.storage.tiering.TieringStorageEngine;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

class Stage2ServerHardeningTest {

    @TempDir
    Path tempDir;

    @Test
    void pipelinedMultiExecAndHello3ExecuteSafelyAcrossMultiShardServer() throws Exception {
        MemTable memTable = MemTable.create();
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "stage2-multi");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(CommandRegistry.createDefault(), memTable, executor))) {
            server.start();
            try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
                socket.setSoTimeout(5000);
                OutputStream out = socket.getOutputStream();
                BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

                // Pipeline HELLO 3 + MULTI + 2 SETs on different keys + EXEC + 2 GETs
                String pipeline = resp("HELLO", "3")
                        + resp("MULTI")
                        + resp("SET", "k1", "v1")
                        + resp("SET", "k2", "v2")
                        + resp("EXEC")
                        + resp("GET", "k1")
                        + resp("GET", "k2");
                out.write(pipeline.getBytes(StandardCharsets.UTF_8));
                out.flush();

                String helloReply = readResp(in);
                assertThat(helloReply).startsWith("%");
                assertThat(readResp(in)).isEqualTo("+OK");
                assertThat(readResp(in)).isEqualTo("+QUEUED");
                assertThat(readResp(in)).isEqualTo("+QUEUED");
                assertThat(readResp(in)).isEqualTo("[+OK, +OK]");
                assertThat(readResp(in)).isEqualTo("$v1");
                assertThat(readResp(in)).isEqualTo("$v2");
            }
        }
    }

    @Test
    void blpopAndLpushOnSameKeyDoNotDeadlockWithSingleShard() throws Exception {
        MemTable memTable = MemTable.create();
        try (KeyShardExecutor executor = new KeyShardExecutor(1, "stage2-single");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(CommandRegistry.createDefault(), memTable, executor))) {
            server.start();
            int port = server.boundPort();

            try (Socket waiter = new Socket("127.0.0.1", port);
                 Socket pusher = new Socket("127.0.0.1", port)) {
                waiter.setSoTimeout(5000);
                pusher.setSoTimeout(5000);

                CompletableFuture<String> blpopResult = CompletableFuture.supplyAsync(() -> {
                    try {
                        waiter.getOutputStream().write(resp("BLPOP", "queue:one", "5")
                                .getBytes(StandardCharsets.UTF_8));
                        waiter.getOutputStream().flush();
                        return readResp(new BufferedInputStream(waiter.getInputStream()));
                    } catch (IOException e) {
                        throw new RuntimeException(e);
                    }
                });

                // Wait until BLPOP is parked outside the single shard thread
                Thread.sleep(100);

                pusher.getOutputStream().write(resp("LPUSH", "queue:one", "payload")
                        .getBytes(StandardCharsets.UTF_8));
                pusher.getOutputStream().flush();
                String pushReply = readResp(new BufferedInputStream(pusher.getInputStream()));

                assertThat(pushReply).isEqualTo(":1");
                assertThat(blpopResult.get(2, TimeUnit.SECONDS)).isEqualTo("[$queue:one, $payload]");
            }
        }
    }

    @Test
    void liveTcpInfoTieringUpdatesAfterFlushAndColdReadsAndFlushDb() throws Exception {
        MemoryManager memory = new MemoryManager(1024 * 1024);
        MemTable memTable = MemTable.create(memory);
        MetricsRegistry metrics = new MetricsRegistry();

        try (WALManager wal = new WALManager(new WALConfig(tempDir.resolve("wal"), 1 << 20, WALConfig.FsyncPolicy.NO));
             ColdStorageEngine cold = new ColdStorageEngine(
                     new ColdStorageEngine.Config(tempDir.resolve("cold"), 4096, 10.0, 256L, 10));
             TieringController tiering = new TieringController(
                     TieringController.Config.defaults(tempDir.resolve("mig")),
                     memory, memTable, wal, cold);
             KeyShardExecutor executor = new KeyShardExecutor(2, "stage2-e2e")) {
            WALStorageEngine walStorage = new WALStorageEngine(wal, memTable);
            HotKeyPolicy policy = HotKeyPolicy.defaults();
            HotKeyReadCache cache = new HotKeyReadCache(new HotKeyDetector(policy), policy, walStorage);
            StorageEngine storage = new TieringReadStorageEngine(
                    new TieringStorageEngine(new HotKeyStorageEngine(walStorage, cache), tiering), cold);

            ServerInfoProvider serverInfo = new ServerInfoProvider(
                    metrics, memory, storage::size, 0, "5.1.0",
                    tiering.metrics(), null, null);
            CommandRegistry registry = CommandRegistry.createDefault(
                    serverInfo::render, Map.copyOf(serverInfo.sections()));
            CommandEngine engine = new CommandEngine(registry, storage, executor, memory::isOverLimit);

            try (TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0), engine, metrics)) {
                server.start();
                try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
                    socket.setSoTimeout(5000);
                    OutputStream out = socket.getOutputStream();
                    BufferedInputStream in = new BufferedInputStream(socket.getInputStream());

                    String payload = "v".repeat(300);
                    out.write(resp("SET", "e2e:k1", payload).getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    assertThat(readResp(in)).isEqualTo("+OK");

                    tiering.flushScheduler().scheduleFlush();
                    long deadline = System.currentTimeMillis() + 5000L;
                    String info = "";
                    while (System.currentTimeMillis() < deadline) {
                        out.write(resp("INFO", "tiering").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                        info = readResp(in);
                        if (info.contains("tiering_cold_sstable_count:1")
                                && info.contains("tiering_memtable_used_bytes:0")) {
                            break;
                        }
                        Thread.sleep(30);
                    }
                    assertThat(info).contains("tiering_cold_sstable_count:1");
                    assertThat(info).contains("tiering_memtable_used_bytes:0");
                    assertThat(info).contains("tiering_flush_count:1");

                    // Read back cold value over TCP, then FLUSHDB
                    out.write((resp("GET", "e2e:k1") + resp("FLUSHDB") + resp("GET", "e2e:k1"))
                            .getBytes(StandardCharsets.UTF_8));
                    out.flush();
                    assertThat(readResp(in)).isEqualTo("$" + payload);
                    assertThat(readResp(in)).isEqualTo("+OK");
                    assertThat(readResp(in)).isEqualTo("<null>");
                }
            }
        }
    }

    private static String resp(String... parts) {
        StringBuilder sb = new StringBuilder("*").append(parts.length).append("\r\n");
        for (String p : parts) {
            byte[] b = p.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(b.length).append("\r\n").append(p).append("\r\n");
        }
        return sb.toString();
    }

    private static String readResp(InputStream in) throws IOException {
        int prefix = in.read();
        if (prefix < 0) {
            throw new IOException("EOF");
        }
        String line = readLine(in);
        return switch (prefix) {
            case '+', '-', ':' -> ((char) prefix) + line;
            case '$' -> {
                int len = Integer.parseInt(line);
                if (len < 0) {
                    yield "<null>";
                }
                byte[] buf = in.readNBytes(len);
                in.readNBytes(2);
                yield "$" + new String(buf, StandardCharsets.UTF_8);
            }
            case '*' -> {
                int count = Integer.parseInt(line);
                if (count < 0) {
                    yield "<null-array>";
                }
                List<String> items = new ArrayList<>(count);
                for (int i = 0; i < count; i++) {
                    items.add(readResp(in));
                }
                yield items.toString();
            }
            case '%' -> {
                int pairs = Integer.parseInt(line);
                List<String> items = new ArrayList<>(pairs * 2);
                for (int i = 0; i < pairs * 2; i++) {
                    items.add(readResp(in));
                }
                yield "%" + items;
            }
            default -> throw new IOException("Unexpected prefix: " + (char) prefix);
        };
    }

    private static String readLine(InputStream in) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        int prev = -1;
        while (true) {
            int cur = in.read();
            if (cur < 0) {
                throw new IOException("Unexpected EOF");
            }
            if (prev == '\r' && cur == '\n') {
                byte[] bytes = out.toByteArray();
                return new String(bytes, 0, bytes.length - 1, StandardCharsets.UTF_8);
            }
            out.write(cur);
            prev = cur;
        }
    }
}
