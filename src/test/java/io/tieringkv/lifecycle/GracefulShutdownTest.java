package io.tieringkv.lifecycle;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.TestRespClient;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import io.tieringkv.storage.wal.WALConfig;
import io.tieringkv.storage.wal.WALManager;
import io.tieringkv.storage.wal.WALStorageEngine;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

class GracefulShutdownTest {

    @TempDir
    Path dir;

    @Test
    void shutdownDrainsRequestsAndPersistsWal() throws Exception {
        WALConfig walConfig = WALConfig.defaults(dir.resolve("wal"));
        MemTable memTable = MemTable.create(new MemoryManager(1L << 30));
        WALManager wal = new WALManager(walConfig);
        MetricsRegistry metrics = new MetricsRegistry();
        TieringKvServer server;
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "shutdown-test")) {
            CommandEngine engine = new CommandEngine(
                    CommandRegistry.createDefault(metrics::infoText),
                    new WALStorageEngine(wal, memTable), executor);
            server = new TieringKvServer(new ServerConfig("127.0.0.1", 0), engine, metrics);
            server.start();
            ShutdownManager shutdownManager = new ShutdownManager(
                    server, metrics, wal, memTable, 5000);

            int ops = 200;
            AtomicInteger responses = new AtomicInteger();
            Thread client = new Thread(() -> {
                try (TestRespClient clientSocket = new TestRespClient(server.boundPort())) {
                    StringBuilder pipeline = new StringBuilder();
                    for (int i = 0; i < ops; i++) {
                        pipeline.append(TestRespClient.command("SET", "k" + i, "v" + i));
                    }
                    clientSocket.send(pipeline.toString());
                    while (responses.get() < ops) {
                        String response = clientSocket.readResponse();
                        if (response == null) {
                            break;
                        }
                        responses.incrementAndGet();
                    }
                } catch (Exception e) {
                    // EOF 视为结束
                }
            });
            client.start();
            Thread.sleep(200); // 让请求进入
            shutdownManager.shutdown();
            client.join(15_000);
            assertThat(metrics.snapshot().activeRequests()).isZero();
            assertThat(responses).hasValue(ops); // 请求不丢失

            // 重启恢复：WAL 完整
            MemTable recovered = MemTable.create(new MemoryManager(1L << 30));
            try (WALManager walAgain = new WALManager(walConfig)) {
                walAgain.recover(recovered);
            }
            assertThat(recovered.size()).isGreaterThan(0);
        } finally {
            wal.close();
        }
    }
}
