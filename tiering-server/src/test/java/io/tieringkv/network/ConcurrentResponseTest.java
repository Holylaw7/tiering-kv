package io.tieringkv.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/** 1000 并发混合键 pipeline：无乱序、无丢失（ADR-0033 保序验证）。 */
class ConcurrentResponseTest {

    @Test
    void thousandMixedKeyRequestsKeepOrderPerConnection() throws Exception {
        MemTable memTable = MemTable.create();
        MetricsRegistry metrics = new MetricsRegistry();
        try (KeyShardExecutor executor = new KeyShardExecutor(8, "order-test")) {
            TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0),
                    new CommandEngine(CommandRegistry.createDefault(), memTable, executor),
                    metrics);
            server.start();
            try {
                int connections = 4;
                int perConnection = 250;
                ExecutorService pool = Executors.newFixedThreadPool(connections);
                CountDownLatch start = new CountDownLatch(1);
                AtomicInteger failures = new AtomicInteger();
                List<Thread> verifiers = new ArrayList<>();
                for (int c = 0; c < connections; c++) {
                    final int clientId = c;
                    verifiers.add(new Thread(() -> {
                        try (TestRespClient client = new TestRespClient(server.boundPort())) {
                            start.await();
                            StringBuilder pipeline = new StringBuilder();
                            for (int i = 0; i < perConnection; i++) {
                                String key = "k" + clientId + ":" + i;
                                String value = "v" + i;
                                pipeline.append(TestRespClient.command("SET", key, value));
                                pipeline.append(TestRespClient.command("GET", key));
                            }
                            client.send(pipeline.toString());
                            for (int i = 0; i < perConnection; i++) {
                                if (!"+OK".equals(client.readResponse())) {
                                    failures.incrementAndGet();
                                }
                                String expected = "$" + ("v" + i).length() + "\r\nv" + i + "\r\n";
                                if (!expected.equals(client.readResponse())) {
                                    failures.incrementAndGet();
                                }
                            }
                        } catch (Exception e) {
                            failures.incrementAndGet();
                        }
                    }));
                }
                for (Thread thread : verifiers) {
                    thread.start();
                }
                start.countDown();
                for (Thread thread : verifiers) {
                    thread.join(30_000);
                }
                pool.shutdown();
                assertThat(pool.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
                assertThat(failures).hasValue(0); // 无乱序、无丢失、无错误
                assertThat(metrics.snapshot().activeRequests()).isZero();
            } finally {
                server.shutdown();
            }
        }
    }
}
