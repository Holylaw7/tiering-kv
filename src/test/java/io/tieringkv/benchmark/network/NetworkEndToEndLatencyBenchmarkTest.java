package io.tieringkv.benchmark.network;

import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.Locale;
import java.util.concurrent.ThreadLocalRandom;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 网络端到端 GET 延迟可复现基准（docs/benchmark/reproducible-benchmark-guide.md）：
 * 回环 + RESP + Command 全链路，固定 1 连接 × pipeline 1 × 10K 数据集，
 * 预热 2,000 次，采样 20,000 次，共 5 轮，输出 NETWORK-BENCH 行。
 *
 * <p>目的：把早期冒烟口径（P99 ≈0.19ms）固化为固定 workload 的可复现
 * 协议，回答“网络端到端延迟的连接数 / pipeline / 样本数”追问。
 */
@Tag("benchmark")
class NetworkEndToEndLatencyBenchmarkTest {

    private static final int DATASET = 10_000;
    private static final int WARMUP = 2_000;
    private static final int SAMPLES = 20_000;
    private static final int ROUNDS = 5;

    @Test
    void getLatencySingleConnectionPipelineOne() throws Exception {
        MemTable memTable = MemTable.create();
        for (int i = 0; i < DATASET; i++) {
            memTable.put(key(i), value(i));
        }
        try (KeyShardExecutor executor = new KeyShardExecutor(4, "net-latency")) {
            TieringKvServer server = new TieringKvServer(
                    new ServerConfig("127.0.0.1", 0),
                    new CommandEngine(CommandRegistry.createDefault(),
                            memTable, executor));
            server.start();
            try {
                for (int round = 1; round <= ROUNDS; round++) {
                    measureRound(server.boundPort(), round);
                }
            } finally {
                server.shutdown();
            }
        }
    }

    private static void measureRound(int port, int round) throws IOException {
        try (Socket socket = new Socket("127.0.0.1", port)) {
            socket.setTcpNoDelay(true);
            DataOutputStream out = new DataOutputStream(
                    socket.getOutputStream());
            DataInputStream in = new DataInputStream(
                    socket.getInputStream());
            ThreadLocalRandom random = ThreadLocalRandom.current();

            for (int i = 0; i < WARMUP; i++) {
                get(out, in, key(random.nextInt(DATASET)));
            }

            long[] latencies = new long[SAMPLES];
            long start = System.nanoTime();
            for (int i = 0; i < SAMPLES; i++) {
                byte[] requestKey = key(random.nextInt(DATASET));
                long t0 = System.nanoTime();
                get(out, in, requestKey);
                latencies[i] = System.nanoTime() - t0;
            }
            long totalNanos = System.nanoTime() - start;
            Arrays.sort(latencies);
            double p50 = latencies[SAMPLES / 2] / 1_000_000.0;
            double p95 = latencies[(int) (SAMPLES * 0.95)] / 1_000_000.0;
            double p99 = latencies[(int) (SAMPLES * 0.99)] / 1_000_000.0;
            double opsPerSecond = SAMPLES / (totalNanos / 1_000_000_000.0);
            System.out.printf(Locale.ROOT,
                    "NETWORK-BENCH GET round=%d p50=%.4fms p95=%.4fms "
                            + "p99=%.4fms throughput=%.0f ops/s%n",
                    round, p50, p95, p99, opsPerSecond);
            assertThat(p99).as("round %d network GET P99", round)
                    .isLessThan(5.0);
        }
    }

    /** 单条 GET：发送 RESP 请求并完整读取 bulk 响应（含 CRLF 尾）。 */
    private static void get(DataOutputStream out, DataInputStream in,
                            byte[] requestKey) throws IOException {
        byte[] key = requestKey;
        byte[] cmd = ("*2\r\n$3\r\nGET\r\n$" + key.length + "\r\n")
                .getBytes(StandardCharsets.UTF_8);
        out.write(cmd);
        out.write(key);
        out.writeBytes("\r\n");
        out.flush();
        readBulk(in);
    }

    private static void readBulk(DataInputStream in) throws IOException {
        int b = in.read();
        if (b == '$') {
            int len = readLineInt(in);
            if (len == -1) {
                readCrlf(in);
                return;
            }
            for (int i = 0; i < len; i++) {
                in.read();
            }
            readCrlf(in);
            return;
        }
        if (b == '-') {
            skipLine(in);
            return;
        }
        throw new IOException("unexpected RESP prefix: " + (char) b);
    }

    private static int readLineInt(DataInputStream in) throws IOException {
        StringBuilder sb = new StringBuilder();
        while (true) {
            int c = in.read();
            if (c == '\r') {
                in.read(); // '\n'
                break;
            }
            if (c == -1) {
                throw new IOException("EOF in RESP line");
            }
            sb.append((char) c);
        }
        return Integer.parseInt(sb.toString());
    }

    private static void readCrlf(DataInputStream in) throws IOException {
        if (in.read() != '\r' || in.read() != '\n') {
            throw new IOException("missing CRLF");
        }
    }

    private static void skipLine(DataInputStream in) throws IOException {
        while (true) {
            int c = in.read();
            if (c == -1 || c == '\n') {
                return;
            }
        }
    }

    private static byte[] key(int i) {
        return String.format(Locale.ROOT, "k%05d", i)
                .getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] value(int i) {
        return String.format(Locale.ROOT, "v%05d", i)
                .getBytes(StandardCharsets.UTF_8);
    }
}
