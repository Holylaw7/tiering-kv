package io.tieringkv;

import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.MultiRaftTransport;
import io.tieringkv.storage.memory.MemTable;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/**
 * 多 Region 集群节点入口（Phase 16）：单 JVM 运行多个 Raft 组，
 * 共享单端口 MultiRaftEndpoint；日志/状态按组目录隔离。
 *
 * <p>参数：--nodeId n1 --port 7000
 * --peers n1@127.0.0.1:7000,n2@127.0.0.1:7001,n3@127.0.0.1:7002
 * [--data ./data]
 */
public final class ClusterMain {

    private ClusterMain() {
    }

    public static void main(String[] args) throws Exception {
        Map<String, String> options = parse(args);
        String nodeId = require(options, "nodeId");
        int port = Integer.parseInt(require(options, "port"));
        Map<String, InetSocketAddress> addresses = parsePeers(
                require(options, "peers"));
        Path dataDir = Path.of(options.getOrDefault("data", "./data"))
                .resolve(nodeId);

        MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, port, addresses);
        MultiRaftNode host = new MultiRaftNode(nodeId);
        RaftGroupManager manager = new RaftGroupManager(
                nodeId, host, new LeaderElection(100, 80), 25, 10);
        endpoint.start();

        List<String> groups = List.of("r1", "r2");
        for (String group : groups) {
            Path groupDir = dataDir.resolve(group);
            MultiRaftTransport transport = new MultiRaftTransport(group, endpoint);
            manager.createGroupPersistent(group, transport, MemTable.create(),
                    FileRaftLog.open(groupDir.resolve("raftlog"), Durability.SYNC),
                    RaftPersistentState.open(groupDir),
                    null);
            endpoint.register(group, manager.raftFor(group));
        }
        manager.startAll();

        System.out.printf("Tiering-KV cluster node %s listening on %d, "
                        + "groups=%s, data=%s%n",
                nodeId, endpoint.boundPort(), groups, dataDir);
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            manager.close();
            endpoint.close();
            latch.countDown();
        }));
        latch.await();
    }

    private static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    private static String require(Map<String, String> options, String key) {
        String value = options.get(key);
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException("missing --" + key);
        }
        return value;
    }

    private static Map<String, InetSocketAddress> parsePeers(String peers) {
        Map<String, InetSocketAddress> addresses = new HashMap<>();
        for (String peer : peers.split(",")) {
            String[] parts = peer.split("@");
            if (parts.length != 2) {
                throw new IllegalArgumentException("invalid peer " + peer);
            }
            String[] hostPort = parts[1].split(":");
            addresses.put(parts[0], new InetSocketAddress(
                    hostPort[0], Integer.parseInt(hostPort[1])));
        }
        return addresses;
    }
}
