package io.tieringkv.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.function.BooleanSupplier;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * TD-082 回归：服务端必须复用调用方传入的 MetricsRegistry，
 * 否则 INFO 读取的实例与实际埋点实例不同，qps/连接数恒为 0。
 */
class ServerMetricsIntegrationTest {

    private TieringKvServer server;
    private MetricsRegistry metrics;

    @BeforeEach
    void start() throws Exception {
        metrics = new MetricsRegistry();
        server = new TieringKvServer(new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(), MemTable.create()),
                metrics);
        server.start();
    }

    @AfterEach
    void stop() {
        server.shutdown();
    }

    @Test
    void countsConnectionsAndCompletedCommands() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$4\r\nPING\r\n");
            assertThat(client.readResponse()).isEqualTo("+PONG");
            client.send("*1\r\n$4\r\nPING\r\n");
            assertThat(client.readResponse()).isEqualTo("+PONG");

            assertThat(awaitUntil(() -> metrics.snapshot().qps() > 0.0)).isTrue();
            assertThat(metrics.snapshot().connections()).isEqualTo(1);
        }
        assertThat(awaitUntil(() -> metrics.snapshot().connections() == 0)).isTrue();
    }

    @Test
    void exposesMetricsThroughInfoText() {
        assertThat(metrics.infoText()).contains("connections:").contains("qps:");
    }

    private static boolean awaitUntil(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return true;
            }
            Thread.sleep(20);
        }
        return false;
    }
}
