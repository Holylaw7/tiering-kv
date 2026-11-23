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

/** 真实客户端 Jepsen（ADR-0322 M4 增强）：RESP 链路线性一致。 */
class VerificationHarnessRespTest {

    private TieringKvServer server;

    @BeforeEach
    void setUp() throws Exception {
        server = new TieringKvServer(
                new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(),
                        MemTable.create()));
        server.start();
    }

    @AfterEach
    void tearDown() throws Exception {
        server.close();
    }

    @Test
    void respStoreHistoryIsLinearizable() throws Exception {
        try (RespVerificationStore store =
                     new RespVerificationStore("127.0.0.1",
                             server.boundPort())) {
            VerificationHarness harness =
                    new VerificationHarness(8, 200, "k");
            VerificationHarness.Report report = harness.run(store);
            assertThat(report.linearizable()).isTrue();
            assertThat(report.operations()).isPositive();
        }
    }

    @Test
    void memoryStoreStillLinearizable() throws Exception {
        VerificationHarness harness =
                new VerificationHarness(8, 200, "k");
        assertThat(harness.run().linearizable()).isTrue();
    }
}
