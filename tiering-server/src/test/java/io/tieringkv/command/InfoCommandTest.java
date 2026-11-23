package io.tieringkv.command;

import io.tieringkv.config.ServerConfig;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.TestRespClient;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class InfoCommandTest {

    @Test
    void infoReturnsServerMetrics() throws Exception {
        MetricsRegistry metrics = new MetricsRegistry();
        TieringKvServer server = new TieringKvServer(
                new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(metrics::infoText), MemTable.create()),
                metrics);
        server.start();
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$4\r\nINFO\r\n");
            String response = client.readResponse();
            assertThat(response).startsWith("$");
            assertThat(response).contains("# Server");
            assertThat(response).contains("connections:");
        } finally {
            server.shutdown();
        }
    }
}
