package io.tieringkv.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 异步（key 分片）执行下，RESP 响应顺序 = 请求顺序。 */
class AsyncServerOrderingTest {

    @Test
    void pipelinedDifferentKeyResponsesStayInOrder() throws Exception {
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "async-order")) {
            TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0),
                    new CommandEngine(CommandRegistry.createDefault(), memTable, executor));
            server.start();
            try (TestRespClient client = new TestRespClient(server.boundPort())) {
                client.send(TestRespClient.command("SET", "a", "1")
                        + TestRespClient.command("SET", "b", "2")
                        + TestRespClient.command("GET", "a")
                        + TestRespClient.command("GET", "b")
                        + TestRespClient.command("PING"));
                assertThat(client.readResponse()).isEqualTo("+OK");
                assertThat(client.readResponse()).isEqualTo("+OK");
                assertThat(client.readResponse()).isEqualTo("$1\r\n1\r\n");
                assertThat(client.readResponse()).isEqualTo("$1\r\n2\r\n");
                assertThat(client.readResponse()).isEqualTo("+PONG");
            } finally {
                server.shutdown();
            }
        }
    }

    @Test
    void sameKeyCommandsKeepOrderThroughShards() throws Exception {
        MemTable memTable = MemTable.createForTest(
                new MutableClock(0), new MemoryManager(1 << 30));
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "async-order2")) {
            TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0),
                    new CommandEngine(CommandRegistry.createDefault(), memTable, executor));
            server.start();
            try (TestRespClient client = new TestRespClient(server.boundPort())) {
                client.send(TestRespClient.command("SET", "k", "1")
                        + TestRespClient.command("GET", "k")
                        + TestRespClient.command("SET", "k", "2")
                        + TestRespClient.command("GET", "k"));
                assertThat(client.readResponse()).isEqualTo("+OK");
                assertThat(client.readResponse()).isEqualTo("$1\r\n1\r\n");
                assertThat(client.readResponse()).isEqualTo("+OK");
                assertThat(client.readResponse()).isEqualTo("$1\r\n2\r\n");
            } finally {
                server.shutdown();
            }
        }
    }
}
