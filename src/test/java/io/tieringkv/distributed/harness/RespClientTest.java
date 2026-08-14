package io.tieringkv.distributed.harness;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 真实 RESP 客户端（ADR-0322 M4 增强）：SET/GET 链路。 */
class RespClientTest {

    private TieringKvServer server;
    private RespClient client;

    @BeforeEach
    void setUp() throws Exception {
        server = new TieringKvServer(
                new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(),
                        MemTable.create()));
        server.start();
        client = new RespClient("127.0.0.1", server.boundPort());
    }

    @AfterEach
    void tearDown() throws Exception {
        client.close();
        server.close();
    }

    @Test
    void putGetRoundTrip() throws Exception {
        client.put("k", "v1");
        assertThat(client.get("k")).isEqualTo("v1");
        client.put("k", "v2");
        assertThat(client.get("k")).isEqualTo("v2");
    }

    @Test
    void missingKeyReturnsNull() throws Exception {
        assertThat(client.get("absent")).isNull();
    }

    @Test
    void unicodeValuesRoundTrip() throws Exception {
        client.put("k", "中文值");
        assertThat(client.get("k")).isEqualTo("中文值");
    }
}
