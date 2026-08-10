package io.tieringkv.benchmark.gateway;

import io.tieringkv.cluster.gateway.NettyClusterGateway;
import io.tieringkv.cluster.gateway.UnifiedClusterGateway;
import io.tieringkv.cluster.region.RegionEpoch;
import io.tieringkv.cluster.region.RegionId;
import io.tieringkv.cluster.routing.RoutingTable;
import io.tieringkv.cluster.routing.RoutingTableEntry;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

import java.io.InputStream;
import java.io.OutputStream;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 真实 TCP 网关基准（Phase 18）：pipeline GET >500K、SET >200K ops/s。
 */
@Tag("benchmark")
class GatewayBenchmarkTest {

    @Test
    void gatewayGetThroughput() throws Exception {
        GatewayResult result = run(200_000, false);
        printf("PHASE18-BENCH GATEWAY GET ops=%d ops/s=%.0f p50=%.3fms "
                        + "p95=%.3fms p99=%.3fms%n",
                result.ops(), result.opsPerSec(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.opsPerSec()).isGreaterThan(500_000);
    }

    @Test
    void gatewaySetThroughput() throws Exception {
        GatewayResult result = run(100_000, true);
        printf("PHASE18-BENCH GATEWAY SET ops=%d ops/s=%.0f p50=%.3fms "
                        + "p95=%.3fms p99=%.3fms%n",
                result.ops(), result.opsPerSec(),
                result.p50Ms(), result.p95Ms(), result.p99Ms());
        assertThat(result.opsPerSec()).isGreaterThan(200_000);
    }

    private GatewayResult run(int totalOps, boolean set) throws Exception {
        MemTable local = MemTable.create();
        RoutingTable table = new RoutingTable();
        table.update(new RoutingTableEntry(new RegionId(1),
                bytes("a"), bytes("z"), 0, 16_383,
                RegionEpoch.INITIAL, "n1", "g1", false));
        UnifiedClusterGateway gateway = new UnifiedClusterGateway(table,
                Map.of("n1", local),
                Map.of("n1", new InetSocketAddress("127.0.0.1", 7001)), "n1");
        NettyClusterGateway server = new NettyClusterGateway(gateway);
        server.start("127.0.0.1", 0);
        int pipeline = 4096;
        byte[] key = bytes("bench:key");
        local.put(key, bytes("v"));
        String command = set
                ? TestRespClient.command("SET", new String(key,
                StandardCharsets.UTF_8), "v")
                : TestRespClient.command("GET", new String(key,
                StandardCharsets.UTF_8));
        String batchWire = command.repeat(pipeline);
        try (Socket socket = new Socket("127.0.0.1", server.boundPort())) {
            OutputStream out = new BufferedOutputStream(socket.getOutputStream());
            InputStream in = new BufferedInputStream(socket.getInputStream());
            byte[] wire = batchWire.getBytes(StandardCharsets.UTF_8);
            long[] latencies = new long[totalOps];
            int completed = 0;
            long start = System.nanoTime();
            while (completed < totalOps) {
                out.write(wire);
                out.flush();
                int toRead = Math.min(pipeline, totalOps - completed);
                for (int i = 0; i < toRead; i++) {
                    long t0 = System.nanoTime();
                    skipResponse(in);
                    latencies[completed++] = System.nanoTime() - t0;
                }
            }
            double seconds = (System.nanoTime() - start) / 1_000_000_000.0;
            java.util.Arrays.sort(latencies);
            return new GatewayResult(totalOps, totalOps / seconds,
                    latencies[totalOps / 2] / 1_000_000.0,
                    latencies[(int) (totalOps * 0.95)] / 1_000_000.0,
                    latencies[(int) (totalOps * 0.99)] / 1_000_000.0);
        } finally {
            server.close();
            local.close();
        }
    }

    private static void skipResponse(InputStream in) throws Exception {
        int first = in.read();
        if (first == -1) {
            throw new IllegalStateException("connection closed");
        }
        if (first == '$') {
            int length = Integer.parseInt(readLine(in));
            if (length >= 0) {
                in.readNBytes(length);
                in.readNBytes(2);
            }
            return;
        }
        if (first == '*') {
            int count = Integer.parseInt(readLine(in));
            for (int i = 0; i < count; i++) {
                skipResponse(in);
            }
            return;
        }
        readLine(in);
    }

    private static String readLine(InputStream in) throws Exception {
        StringBuilder sb = new StringBuilder();
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                if (sb.length() > 0 && sb.charAt(sb.length() - 1) == '\r') {
                    sb.setLength(sb.length() - 1);
                }
                return sb.toString();
            }
            sb.append((char) b);
        }
        throw new IllegalStateException("connection closed");
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static void printf(String format, Object... args) {
        System.out.printf(Locale.ROOT, format, args);
    }

    private record GatewayResult(int ops, double opsPerSec,
                                 double p50Ms, double p95Ms, double p99Ms) {
    }

    /** 精简 RESP 命令构造（避免依赖测试包）。 */
    private static final class TestRespClient {
        private static String command(String name, String... args) {
            StringBuilder sb = new StringBuilder();
            sb.append('*').append(args.length + 1).append("\r\n");
            appendBulk(sb, name);
            for (String arg : args) {
                appendBulk(sb, arg);
            }
            return sb.toString();
        }

        private static void appendBulk(StringBuilder sb, String value) {
            byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
            sb.append('$').append(bytes.length).append("\r\n")
                    .append(value).append("\r\n");
        }
    }
}
