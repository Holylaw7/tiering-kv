package io.tieringkv.benchmark;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.InMemoryKVStore;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.network.TestRespClient;
import io.tieringkv.network.tcp.TieringKvServer;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.util.Arrays;
import java.util.Locale;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 延迟冒烟基准（Phase 1 基线）：本机回环 GET 延迟 P50/P95/P99。
 * 断言阈值宽松（P50 &lt; 5ms），避免 CI 环境抖动误报；
 * 正式基准由 Phase 9 的 JMH 套件承担。
 */
@Tag("benchmark")
class LatencySmokeTest {

    @Test
    void hotGetLatencyBaseline() throws Exception {
        TieringKvServer server = new TieringKvServer(
                new ServerConfig("127.0.0.1", 0),
                new CommandEngine(CommandRegistry.createDefault(), new InMemoryKVStore()));
        server.start();
        try (TestRespClient client = new TestRespClient(server.boundPort())) {
            client.send(TestRespClient.command("SET", "hot", "value"));
            client.readResponse();

            for (int i = 0; i < 200; i++) {
                client.send(TestRespClient.command("GET", "hot"));
                client.readResponse();
            }

            int samples = 5000;
            long[] latenciesNanos = new long[samples];
            long startNanos = System.nanoTime();
            for (int i = 0; i < samples; i++) {
                long t0 = System.nanoTime();
                client.send(TestRespClient.command("GET", "hot"));
                client.readResponse();
                latenciesNanos[i] = System.nanoTime() - t0;
            }
            long totalNanos = System.nanoTime() - startNanos;

            Arrays.sort(latenciesNanos);
            double p50 = latenciesNanos[samples / 2] / 1_000_000.0;
            double p95 = latenciesNanos[(int) (samples * 0.95)] / 1_000_000.0;
            double p99 = latenciesNanos[(int) (samples * 0.99)] / 1_000_000.0;
            double opsPerSecond = samples / (totalNanos / 1_000_000_000.0);

            System.out.printf(Locale.ROOT,
                    "latency smoke: P50=%.3fms P95=%.3fms P99=%.3fms throughput=%.0f ops/s%n",
                    p50, p95, p99, opsPerSecond);

            assertThat(p50).as("hot GET P50").isLessThan(5.0);
            assertThat(opsPerSecond).as("hot GET throughput").isGreaterThan(1000);
        } finally {
            server.shutdown();
        }
    }
}
