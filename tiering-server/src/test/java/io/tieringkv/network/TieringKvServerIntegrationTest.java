package io.tieringkv.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * Phase 1 集成测试：真实 TCP 连接 + RESP2 协议（模拟 redis-cli 行为）。
 */
class TieringKvServerIntegrationTest {

    private TieringKvServer server;

    @BeforeEach
    void startServer() throws Exception {
        server = new TieringKvServer(
                new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(), MemTable.create()));
        server.start();
    }

    @AfterEach
    void stopServer() {
        server.shutdown();
    }

    @Test
    void pingReturnsPong() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$4\r\nPING\r\n");
            assertThat(client.readResponse()).isEqualTo("+PONG");
        }
    }

    @Test
    void setGetDelRoundTrip() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*3\r\n$3\r\nSET\r\n$3\r\nkey\r\n$5\r\nvalue\r\n");
            assertThat(client.readResponse()).isEqualTo("+OK");

            client.send("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");
            assertThat(client.readResponse()).isEqualTo("$5\r\nvalue\r\n");

            client.send("*2\r\n$3\r\nDEL\r\n$3\r\nkey\r\n");
            assertThat(client.readResponse()).isEqualTo(":1");

            client.send("*2\r\n$3\r\nGET\r\n$3\r\nkey\r\n");
            assertThat(client.readResponse()).isEqualTo("$-1");
        }
    }

    @Test
    void unknownCommandReturnsError() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$3\r\nFOO\r\n");
            assertThat(client.readResponse()).isEqualTo("-ERR unknown command 'foo'");
        }
    }

    @Test
    void wrongArityReturnsError() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$3\r\nGET\r\n");
            assertThat(client.readResponse())
                    .isEqualTo("-ERR wrong number of arguments for 'get' command");
        }
    }

    @Test
    void pipelinedCommandsAreAnsweredInOrder() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("*1\r\n$4\r\nPING\r\n"
                    + "*3\r\n$3\r\nSET\r\n$1\r\na\r\n$1\r\nb\r\n"
                    + "*2\r\n$3\r\nGET\r\n$1\r\na\r\n");
            assertThat(client.readResponse()).isEqualTo("+PONG");
            assertThat(client.readResponse()).isEqualTo("+OK");
            assertThat(client.readResponse()).isEqualTo("$1\r\nb\r\n");
        }
    }

    @Test
    void inlineCommandIsSupported() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("PING\r\n");
            assertThat(client.readResponse()).isEqualTo("+PONG");
        }
    }

    @Test
    void protocolErrorWritesErrorAndClosesConnection() throws Exception {
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send("$abc\r\n");
            assertThat(client.readResponse()).isEqualTo("-ERR Protocol error: invalid bulk length");
            assertThatThrownBy(client::readResponse).isInstanceOf(IOException.class);
        }
    }

    @Test
    void binarySafeKeysAndValues() throws Exception {
        byte[] key = new byte[]{'k', '\r', '\n', 0};
        byte[] value = new byte[]{'v', '\r', '\n', 0, 'x'};
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.sendBytes(arrayCommand("SET", key, value));
            assertThat(client.readResponse()).isEqualTo("+OK");
            client.sendBytes(arrayCommand("GET", key));
            assertThat(client.readResponse()).isEqualTo("$5\r\nv\r\n\u0000x\r\n");
        }
    }

    @Test
    void concurrentConnectionsAreSafe() throws Exception {
        int threads = 8;
        int perThread = 50;
        AtomicInteger failures = new AtomicInteger();
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        List<Future<?>> futures = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int threadId = t;
            futures.add(pool.submit(() -> {
                try (TestRespClient client = new TestRespClient(server.boundPort())) {
                    for (int i = 0; i < perThread; i++) {
                        String key = "k" + threadId + "-" + i;
                        String value = "v" + i;
                        client.send(TestRespClient.command("SET", key, value));
                        if (!"+OK".equals(client.readResponse())) {
                            failures.incrementAndGet();
                        }
                        client.send(TestRespClient.command("GET", key));
                        String expected = "$" + value.length() + "\r\n" + value + "\r\n";
                        if (!expected.equals(client.readResponse())) {
                            failures.incrementAndGet();
                        }
                    }
                } catch (Exception e) {
                    failures.incrementAndGet();
                }
            }));
        }
        for (Future<?> future : futures) {
            future.get();
        }
        pool.shutdown();
        assertThat(failures).hasValue(0);
    }

    private static byte[] arrayCommand(String name, byte[]... args) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(("*" + (args.length + 1) + "\r\n").getBytes(StandardCharsets.US_ASCII));
        writeBulk(out, name.getBytes(StandardCharsets.US_ASCII));
        for (byte[] arg : args) {
            writeBulk(out, arg);
        }
        return out.toByteArray();
    }

    private static void writeBulk(ByteArrayOutputStream out, byte[] data) throws IOException {
        out.write(("$" + data.length + "\r\n").getBytes(StandardCharsets.US_ASCII));
        out.write(data);
        out.write("\r\n".getBytes(StandardCharsets.US_ASCII));
    }
}
