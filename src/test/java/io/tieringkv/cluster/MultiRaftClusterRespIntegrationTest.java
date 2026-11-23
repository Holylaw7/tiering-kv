package io.tieringkv.cluster;

import io.tieringkv.ClusterMain;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.BufferedInputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * 里程碑 M2（A1）：三节点 Multi-Raft 集群通过 Netty RESP 端口对外提供读写、MOVED 重定向与复制验证。
 */
class MultiRaftClusterRespIntegrationTest {

    @TempDir
    Path tempDir;

    @Test
    void threeNodeMultiRaftServesRespRedirectsFollowerWritesAndReplicates() throws Exception {
        int r1 = freePort(), r2 = freePort(), r3 = freePort();
        int c1 = freePort(), c2 = freePort(), c3 = freePort();
        String raftPeers = "n1@127.0.0.1:" + r1 + ",n2@127.0.0.1:" + r2 + ",n3@127.0.0.1:" + r3;
        String respPeers = "n1@127.0.0.1:" + c1 + ",n2@127.0.0.1:" + c2 + ",n3@127.0.0.1:" + c3;

        List<ClusterMain.RunningClusterNode> nodes = new ArrayList<>();
        try {
            nodes.add(ClusterMain.startNode(Map.of(
                    "nodeId", "n1", "port", String.valueOf(r1), "peers", raftPeers,
                    "groups", "r1,r2", "respHost", "127.0.0.1", "respPort", String.valueOf(c1),
                    "respPeers", respPeers, "data", tempDir.toString())));
            nodes.add(ClusterMain.startNode(Map.of(
                    "nodeId", "n2", "port", String.valueOf(r2), "peers", raftPeers,
                    "groups", "r1,r2", "respHost", "127.0.0.1", "respPort", String.valueOf(c2),
                    "respPeers", respPeers, "data", tempDir.toString())));
            nodes.add(ClusterMain.startNode(Map.of(
                    "nodeId", "n3", "port", String.valueOf(r3), "peers", raftPeers,
                    "groups", "r1,r2", "respHost", "127.0.0.1", "respPort", String.valueOf(c3),
                    "respPeers", respPeers, "data", tempDir.toString())));

            ClusterMain.RunningClusterNode leaderR1 = awaitLeader(nodes, "r1", 10_000);
            ClusterMain.RunningClusterNode leaderR2 = awaitLeader(nodes, "r2", 10_000);
            assertThat(leaderR1).isNotNull();
            assertThat(leaderR2).isNotNull();

            // 找一个路由到 r1 的键与对应的 follower
            String key = findKeyForGroup(leaderR1, "r1");
            int expectedSlot = HashSlotRouter.slot(key.getBytes(StandardCharsets.UTF_8));
            ClusterMain.RunningClusterNode follower = nodes.stream()
                    .filter(n -> !n.nodeId().equals(leaderR1.nodeId()))
                    .findFirst()
                    .orElseThrow();

            // 等待 follower 收到 leader 心跳
            awaitLeaderKnown(follower, "r1", leaderR1.nodeId(), 5_000);

            // 1. 写 follower 返回 -MOVED <slot> 127.0.0.1:<leaderRespPort>，且连接不关闭
            try (RespConn fc = new RespConn(follower.respPort())) {
                String moved = fc.cmdLine("SET", key, "v1");
                assertThat(moved).isEqualTo("-MOVED " + expectedSlot + " 127.0.0.1:" + leaderR1.respPort());
                // 连接仍然保持健康可用
                assertThat(fc.cmdLine("PING")).isEqualTo("+PONG");
            }

            // 2. 跟随重定向到 leader 执行二进制安全写 + INCR + INFO cluster
            byte[] binaryVal = new byte[]{0x01, 0x02, '\r', '\n', (byte) 0xFE, 'Z'};
            try (RespConn lc = new RespConn(leaderR1.respPort())) {
                assertThat(lc.cmdLineBytes("SET".getBytes(StandardCharsets.UTF_8),
                        key.getBytes(StandardCharsets.UTF_8), binaryVal)).isEqualTo("+OK");
                byte[] got = lc.cmdBulk("GET", key);
                assertThat(got).isEqualTo(binaryVal);

                String cntKey = findKeyForGroup(leaderR1, "r1");
                assertThat(lc.cmdLine("INCR", cntKey)).isEqualTo(":1");
                assertThat(lc.cmdLine("INCR", cntKey)).isEqualTo(":2");

                String infoCluster = new String(lc.cmdBulk("INFO", "cluster"), StandardCharsets.UTF_8);
                assertThat(infoCluster).contains("cluster_enabled:1", "cluster_groups:2", "group_r1:role=LEADER");
            }

            // 3. 验证 Raft 复制已同步到 follower 的本地状态机
            long deadline = System.currentTimeMillis() + 5_000;
            byte[] replicated = null;
            while (System.currentTimeMillis() < deadline) {
                try (RespConn fc = new RespConn(follower.respPort())) {
                    replicated = fc.cmdBulk("GET", key);
                    if (replicated != null) {
                        break;
                    }
                }
                Thread.sleep(50);
            }
            assertThat(replicated).isEqualTo(binaryVal);
        } finally {
            for (ClusterMain.RunningClusterNode node : nodes) {
                node.close();
            }
        }
    }

    private static String findKeyForGroup(ClusterMain.RunningClusterNode node, String group) {
        for (int i = 0; i < 10_000; i++) {
            String candidate = "cluster:key:" + i + ":" + System.nanoTime();
            if (group.equals(node.storage().groupFor(candidate.getBytes(StandardCharsets.UTF_8)))) {
                return candidate;
            }
        }
        throw new AssertionError("no key mapped to group " + group);
    }

    private static ClusterMain.RunningClusterNode awaitLeader(
            List<ClusterMain.RunningClusterNode> nodes, String group, long timeoutMs) throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            for (ClusterMain.RunningClusterNode n : nodes) {
                if (n.manager().raftFor(group).state() == RaftState.LEADER) {
                    return n;
                }
            }
            Thread.sleep(50);
        }
        return null;
    }

    private static void awaitLeaderKnown(
            ClusterMain.RunningClusterNode follower, String group, String leaderId, long timeoutMs)
            throws InterruptedException {
        long deadline = System.currentTimeMillis() + timeoutMs;
        while (System.currentTimeMillis() < deadline) {
            if (leaderId.equals(follower.manager().raftFor(group).leaderId())) {
                return;
            }
            Thread.sleep(50);
        }
    }

    private static int freePort() throws IOException {
        try (ServerSocket ss = new ServerSocket(0)) {
            return ss.getLocalPort();
        }
    }

    private static final class RespConn implements AutoCloseable {
        private final Socket socket;
        private final BufferedInputStream in;
        private final OutputStream out;

        RespConn(int port) throws IOException {
            this.socket = new Socket("127.0.0.1", port);
            this.socket.setSoTimeout(5000);
            this.in = new BufferedInputStream(socket.getInputStream());
            this.out = socket.getOutputStream();
        }

        String cmdLine(String... parts) throws IOException {
            byte[][] raw = new byte[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                raw[i] = parts[i].getBytes(StandardCharsets.UTF_8);
            }
            return cmdLineBytes(raw);
        }

        String cmdLineBytes(byte[]... parts) throws IOException {
            send(parts);
            return readLine();
        }

        byte[] cmdBulk(String... parts) throws IOException {
            byte[][] raw = new byte[parts.length][];
            for (int i = 0; i < parts.length; i++) {
                raw[i] = parts[i].getBytes(StandardCharsets.UTF_8);
            }
            send(raw);
            String header = readLine();
            if ("$-1".equals(header)) {
                return null;
            }
            assertThat(header).startsWith("$");
            int len = Integer.parseInt(header.substring(1));
            byte[] buf = in.readNBytes(len);
            int cr = in.read();
            int lf = in.read();
            assertThat(cr).isEqualTo('\r');
            assertThat(lf).isEqualTo('\n');
            return buf;
        }

        private void send(byte[]... parts) throws IOException {
            out.write(("*" + parts.length + "\r\n").getBytes(StandardCharsets.UTF_8));
            for (byte[] p : parts) {
                out.write(("$" + p.length + "\r\n").getBytes(StandardCharsets.UTF_8));
                out.write(p);
                out.write("\r\n".getBytes(StandardCharsets.UTF_8));
            }
            out.flush();
        }

        private String readLine() throws IOException {
            StringBuilder sb = new StringBuilder();
            int prev = -1;
            while (true) {
                int b = in.read();
                if (b == -1) {
                    throw new EOFException("EOF after: " + sb);
                }
                if (b == '\n' && prev == '\r') {
                    sb.setLength(sb.length() - 1);
                    return sb.toString();
                }
                sb.append((char) b);
                prev = b;
            }
        }

        @Override
        public void close() throws IOException {
            socket.close();
        }
    }
}
