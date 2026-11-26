package io.tieringkv.runtime;

import io.tieringkv.runtime.CoordinatorRuntime.RuntimeCoordinator;
import io.tieringkv.runtime.GatewayRuntime.RunningGateway;
import io.tieringkv.runtime.MetadataRuntime.RunningMetadata;
import io.tieringkv.runtime.ParticipantRuntime.RunningParticipant;
import io.tieringkv.testkit.TestPorts;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 分布式网关运维能力闭环测试（v6.1.0 A1）：
 * 真 TCP 启动 Metadata + 2 Participant + Coordinator + Gateway，端到端验证
 * DBSIZE / SCAN / SLOWLOG / INFO(# Cluster, # Transaction, # Persistence) /
 * BGSAVE / LASTSAVE / 物理快照原子落盘与 Participant 重启恢复。
 */
class GatewayRuntimeOpsTest {

    @TempDir
    Path tempDir;

    @Test
    void distributedGatewayOpsAndSnapshotRestartRecovery() throws Exception {
        Path metaDir = tempDir.resolve("meta");
        Path p1Dir = tempDir.resolve("p1");
        Path p2Dir = tempDir.resolve("p2");
        Files.createDirectories(metaDir);
        Files.createDirectories(p1Dir);
        Files.createDirectories(p2Dir);

        int metaPort = TestPorts.freePort();
        int p1Port = TestPorts.freePort();
        int p2Port = TestPorts.freePort();
        int coordPort = TestPorts.freePort();
        int gwPort = TestPorts.freePort();

        try (RunningMetadata meta = MetadataRuntime.startServer(Map.of(
                "node-id", "meta-1",
                "rpc-port", String.valueOf(metaPort),
                "data-dir", metaDir.toString()))) {

            Path snap1;
            Path snap2;

            try (RunningParticipant p1 = ParticipantRuntime.startServer(Map.of(
                    "node-id", "p1",
                    "region-id", "r1",
                    "rpc-port", String.valueOf(p1Port),
                    "data-dir", p1Dir.toString()));
                 RunningParticipant p2 = ParticipantRuntime.startServer(Map.of(
                         "node-id", "p2",
                         "region-id", "r2",
                         "rpc-port", String.valueOf(p2Port),
                         "data-dir", p2Dir.toString()))) {

                String regionsSpec = "r1@127.0.0.1:" + p1.boundPort()
                        + ",r2@127.0.0.1:" + p2.boundPort();
                RuntimeCoordinator coordinator = RuntimeCoordinator.start(Map.of(
                        "node-id", "gw-ops",
                        "rpc-port", String.valueOf(coordPort),
                        "metadata-node", "127.0.0.1",
                        "metadata-port", String.valueOf(meta.boundPort()),
                        "regions", regionsSpec));

                try (RunningGateway gateway = GatewayRuntime.startServer(coordinator, gwPort, null);
                     RespClient client = new RespClient("127.0.0.1", gateway.port())) {

                    assertThat(client.command("PING")).isEqualTo("PONG");

                    for (int i = 0; i < 20; i++) {
                        assertThat(client.command("SET", "ops:k" + i, "val-" + i)).isEqualTo("OK");
                    }
                    for (int i = 0; i < 20; i++) {
                        assertThat(client.command("GET", "ops:k" + i)).isEqualTo("val-" + i);
                    }

                    // 1. 跨 Region 精确 DBSIZE
                    assertThat(client.command("DBSIZE")).isEqualTo(20L);

                    // 2. 跨 Region 聚合 SCAN 与 MATCH 过滤
                    @SuppressWarnings("unchecked")
                    List<Object> scanAll = (List<Object>) client.command("SCAN", "0", "COUNT", "100");
                    assertThat(scanAll.get(0)).isEqualTo("0");
                    @SuppressWarnings("unchecked")
                    List<String> keys = (List<String>) scanAll.get(1);
                    assertThat(keys).hasSize(20);
                    assertThat(keys).contains("ops:k0", "ops:k1", "ops:k19");

                    @SuppressWarnings("unchecked")
                    List<Object> scanFiltered = (List<Object>) client.command(
                            "SCAN", "0", "MATCH", "ops:k1*", "COUNT", "100");
                    @SuppressWarnings("unchecked")
                    List<String> filteredKeys = (List<String>) scanFiltered.get(1);
                    assertThat(filteredKeys).hasSize(11);

                    // 3. SLOWLOG 接线正常（无 unknown command）
                    assertThat((Long) client.command("SLOWLOG", "LEN")).isGreaterThanOrEqualTo(0L);
                    assertThat(client.command("SLOWLOG", "GET", "10")).isInstanceOf(List.class);

                    // 4. INFO 全量与分节（# Cluster / # Transaction / # Persistence）
                    String fullInfo = (String) client.command("INFO");
                    assertThat(fullInfo)
                            .contains("# Cluster\r\n")
                            .contains("cluster_enabled:1\r\n")
                            .contains("cluster_mode:distributed\r\n")
                            .contains("cluster_node_id:gw-ops\r\n")
                            .contains("cluster_regions:2\r\n")
                            .contains("cluster_slots_total:16384\r\n")
                            .contains("region_0:r1@127.0.0.1:" + p1.boundPort() + "\r\n")
                            .contains("region_1:r2@127.0.0.1:" + p2.boundPort() + "\r\n")
                            .contains("# Transaction\r\n")
                            .contains("begin_txn:20\r\n")
                            .contains("committed_txn:20\r\n")
                            .contains("txn_region_count:1\r\n")
                            .contains("# Persistence\r\n")
                            .contains("rdb_last_bgsave_status:ok\r\n");

                    assertThat((String) client.command("INFO", "cluster"))
                            .contains("cluster_mode:distributed")
                            .contains("region_1:r2@127.0.0.1:");
                    assertThat((String) client.command("INFO", "transaction"))
                            .contains("# Transaction")
                            .contains("committed_txn:20");
                    assertThat((String) client.command("INFO", "txn"))
                            .contains("# Transaction")
                            .contains("begin_txn:20");

                    // 5. 分布式 BGSAVE + LASTSAVE + 物理快照落盘
                    assertThat(client.command("BGSAVE")).isEqualTo("Background saving started");
                    long lastSave = awaitLastSave(client, 5_000);
                    assertThat(lastSave).isGreaterThan(0L);

                    String persistenceInfo = (String) client.command("INFO", "persistence");
                    assertThat(persistenceInfo)
                            .contains("rdb_bgsave_in_progress:0\r\n")
                            .contains("rdb_last_bgsave_status:ok\r\n")
                            .contains("rdb_last_save_time:" + lastSave + "\r\n");

                    snap1 = p1.snapshotFile();
                    snap2 = p2.snapshotFile();
                    assertThat(Files.isRegularFile(snap1)).isTrue();
                    assertThat(Files.isRegularFile(snap2)).isTrue();
                    assertThat(Files.size(snap1)).isGreaterThan(12L);
                    assertThat(Files.size(snap2)).isGreaterThan(12L);
                }
            }

            // 6. 模拟 Participant 进程重启：从物理快照恢复全部数据并重新提供 RESP 读写
            int restartedP1Port = TestPorts.freePort();
            int restartedP2Port = TestPorts.freePort();
            int restartedCoordPort = TestPorts.freePort();
            int restartedGwPort = TestPorts.freePort();

            try (RunningParticipant p1Again = ParticipantRuntime.startServer(Map.of(
                    "node-id", "p1",
                    "region-id", "r1",
                    "rpc-port", String.valueOf(restartedP1Port),
                    "data-dir", p1Dir.toString()));
                 RunningParticipant p2Again = ParticipantRuntime.startServer(Map.of(
                         "node-id", "p2",
                         "region-id", "r2",
                         "rpc-port", String.valueOf(restartedP2Port),
                         "data-dir", p2Dir.toString()))) {

                assertThat(p1Again.restoredEntries()).isGreaterThan(0);
                assertThat(p2Again.restoredEntries()).isGreaterThan(0);
                assertThat(p1Again.restoredEntries() + p2Again.restoredEntries()).isEqualTo(20);

                String restartedSpec = "r1@127.0.0.1:" + p1Again.boundPort()
                        + ",r2@127.0.0.1:" + p2Again.boundPort();
                RuntimeCoordinator coordinatorAgain = RuntimeCoordinator.start(Map.of(
                        "node-id", "gw-ops-recovered",
                        "rpc-port", String.valueOf(restartedCoordPort),
                        "metadata-node", "127.0.0.1",
                        "metadata-port", String.valueOf(meta.boundPort()),
                        "regions", restartedSpec));

                try (RunningGateway gatewayAgain = GatewayRuntime.startServer(
                        coordinatorAgain, restartedGwPort, null);
                     RespClient client = new RespClient("127.0.0.1", gatewayAgain.port())) {
                    assertThat(client.command("DBSIZE")).isEqualTo(20L);
                    for (int i = 0; i < 20; i++) {
                        assertThat(client.command("GET", "ops:k" + i)).isEqualTo("val-" + i);
                    }
                }
            }
        }
    }

    @Test
    void bgsaveRecordsErrorWhenParticipantUnreachable() throws Exception {
        Path metaDir = tempDir.resolve("err-meta");
        Path p1Dir = tempDir.resolve("err-p1");
        Path p2Dir = tempDir.resolve("err-p2");
        Files.createDirectories(metaDir);
        Files.createDirectories(p1Dir);
        Files.createDirectories(p2Dir);

        int metaPort = TestPorts.freePort();
        int p1Port = TestPorts.freePort();
        int p2Port = TestPorts.freePort();
        int coordPort = TestPorts.freePort();
        int gwPort = TestPorts.freePort();

        try (RunningMetadata meta = MetadataRuntime.startServer(Map.of(
                "node-id", "meta-err",
                "rpc-port", String.valueOf(metaPort),
                "data-dir", metaDir.toString()));
             RunningParticipant p1 = ParticipantRuntime.startServer(Map.of(
                     "node-id", "p1",
                     "region-id", "r1",
                     "rpc-port", String.valueOf(p1Port),
                     "data-dir", p1Dir.toString()))) {

            RunningParticipant p2 = ParticipantRuntime.startServer(Map.of(
                    "node-id", "p2",
                    "region-id", "r2",
                    "rpc-port", String.valueOf(p2Port),
                    "data-dir", p2Dir.toString()));
            int p2BoundPort = p2.boundPort();

            String regionsSpec = "r1@127.0.0.1:" + p1.boundPort()
                    + ",r2@127.0.0.1:" + p2BoundPort;
            RuntimeCoordinator coordinator = RuntimeCoordinator.start(Map.of(
                    "node-id", "gw-err",
                    "rpc-port", String.valueOf(coordPort),
                    "metadata-node", "127.0.0.1",
                    "metadata-port", String.valueOf(meta.boundPort()),
                    "regions", regionsSpec));

            try (RunningGateway gateway = GatewayRuntime.startServer(coordinator, gwPort, null);
                 RespClient client = new RespClient("127.0.0.1", gateway.port())) {

                // 关闭 r2，模拟单 Region 不可达
                p2.close();

                assertThat(client.command("BGSAVE")).isEqualTo("Background saving started");
                String persistence = awaitBgsaveStatus(client, "err", 12_000);
                assertThat(persistence)
                        .contains("rdb_bgsave_in_progress:0\r\n")
                        .contains("rdb_last_bgsave_status:err\r\n")
                        .contains("rdb_last_bgsave_error:");
            }
        }
    }

    private static long awaitLastSave(RespClient client, long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        while (System.currentTimeMillis() < deadline) {
            long ts = (Long) client.command("LASTSAVE");
            if (ts > 0) {
                return ts;
            }
            Thread.sleep(50);
        }
        return (Long) client.command("LASTSAVE");
    }

    private static String awaitBgsaveStatus(RespClient client, String expectedStatus,
                                            long timeoutMillis) throws Exception {
        long deadline = System.currentTimeMillis() + timeoutMillis;
        String latest = "";
        while (System.currentTimeMillis() < deadline) {
            latest = (String) client.command("INFO", "persistence");
            if (latest.contains("rdb_bgsave_in_progress:0\r\n")
                    && latest.contains("rdb_last_bgsave_status:" + expectedStatus + "\r\n")) {
                return latest;
            }
            Thread.sleep(100);
        }
        return latest;
    }

    private static final class RespClient implements AutoCloseable {
        private final Socket socket;
        private final OutputStream out;
        private final InputStream in;

        RespClient(String host, int port) throws IOException {
            this.socket = new Socket(host, port);
            this.socket.setSoTimeout(10_000);
            this.out = socket.getOutputStream();
            this.in = new BufferedInputStream(socket.getInputStream());
        }

        Object command(String... parts) throws IOException {
            StringBuilder sb = new StringBuilder();
            sb.append('*').append(parts.length).append("\r\n");
            for (String part : parts) {
                byte[] bytes = part.getBytes(StandardCharsets.UTF_8);
                sb.append('$').append(bytes.length).append("\r\n").append(part).append("\r\n");
            }
            out.write(sb.toString().getBytes(StandardCharsets.UTF_8));
            out.flush();
            return readValue();
        }

        private Object readValue() throws IOException {
            int prefix = in.read();
            if (prefix == -1) {
                throw new IOException("unexpected EOF");
            }
            return switch (prefix) {
                case '+' -> readLine();
                case '-' -> throw new IOException("RESP error: " + readLine());
                case ':' -> Long.parseLong(readLine());
                case '$' -> {
                    int len = Integer.parseInt(readLine());
                    if (len < 0) {
                        yield null;
                    }
                    byte[] buf = in.readNBytes(len);
                    if (buf.length != len) {
                        throw new IOException("truncated bulk string");
                    }
                    if (in.read() != '\r' || in.read() != '\n') {
                        throw new IOException("missing CRLF after bulk");
                    }
                    yield new String(buf, StandardCharsets.UTF_8);
                }
                case '*' -> {
                    int count = Integer.parseInt(readLine());
                    if (count < 0) {
                        yield null;
                    }
                    List<Object> list = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        list.add(readValue());
                    }
                    yield list;
                }
                default -> throw new IOException("unexpected RESP prefix: " + (char) prefix);
            };
        }

        private String readLine() throws IOException {
            ByteArrayOutputStream baos = new ByteArrayOutputStream();
            while (true) {
                int b = in.read();
                if (b == -1) {
                    throw new IOException("unexpected EOF in line");
                }
                if (b == '\r') {
                    int next = in.read();
                    if (next != '\n') {
                        throw new IOException("expected LF after CR");
                    }
                    return baos.toString(StandardCharsets.UTF_8);
                }
                baos.write(b);
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
