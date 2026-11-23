package io.tieringkv;

import io.tieringkv.cluster.multiraft.MultiRaftNode;
import io.tieringkv.cluster.multiraft.MultiRaftStorageEngine;
import io.tieringkv.cluster.multiraft.RaftGroupManager;
import io.tieringkv.cluster.raft.LeaderElection;
import io.tieringkv.cluster.raft.log.Durability;
import io.tieringkv.cluster.raft.log.FileRaftLog;
import io.tieringkv.cluster.raft.log.RaftPersistentState;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.MultiRaftTransport;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RuntimeConfigBridge;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.observability.OpsLogger;
import io.tieringkv.observability.ServerInfoProvider;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.storage.memory.MemTable;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Arrays;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.function.Supplier;

/**
 * 多 Region 集群节点入口（Phase 16 + M2-A1）：单 JVM 运行多个 Raft 组，
 * 共享单端口 MultiRaftEndpoint，并可选挂载 Netty RESP 服务端口对外提供集群读写。
 *
 * <p>参数：--nodeId n1 --port 7000
 * --peers n1@127.0.0.1:7000,n2@127.0.0.1:7001,n3@127.0.0.1:7002
 * [--groups r1,r2] [--respPort 6379] [--respPeers n1@127.0.0.1:6379,...]
 * [--password secret] [--data ./data]
 */
public final class ClusterMain {

    private ClusterMain() {
    }

    public static void main(String[] args) throws Exception {
        RunningClusterNode node = startNode(parse(args));
        CountDownLatch latch = new CountDownLatch(1);
        Runtime.getRuntime().addShutdownHook(new Thread(() -> {
            OpsLogger.shutdown("cluster-node");
            node.close();
            latch.countDown();
        }));
        latch.await();
    }

    /** 可测试启停句柄：供集成测试与独立进程复用。 */
    public static RunningClusterNode startNode(Map<String, String> options) throws Exception {
        String nodeId = require(options, "nodeId");
        int port = Integer.parseInt(require(options, "port"));
        Map<String, InetSocketAddress> addresses = parsePeers(require(options, "peers"));
        Path dataDir = Path.of(options.getOrDefault("data", "./data")).resolve(nodeId);
        List<String> groups = parseGroups(options.getOrDefault("groups", "r1,r2"));
        int respPort = options.containsKey("respPort")
                ? Integer.parseInt(options.get("respPort"))
                : -1;
        String respHost = options.getOrDefault("respHost", "0.0.0.0");
        String password = options.get("password");

        MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, port, addresses);
        MultiRaftNode host = new MultiRaftNode(nodeId);
        RaftGroupManager manager = new RaftGroupManager(
                nodeId, host, new LeaderElection(100, 80), 25, 10);
        endpoint.start();

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

        TieringKvServer respServer = null;
        KeyShardExecutor shardExecutor = null;
        MultiRaftStorageEngine storage = null;
        if (respPort >= 0) {
            Map<String, InetSocketAddress> respPeers = options.containsKey("respPeers")
                    ? parsePeers(options.get("respPeers"))
                    : deriveRespPeers(addresses, respPort);
            storage = new MultiRaftStorageEngine(groups, manager, respPeers);
            AclPolicy aclPolicy = new AclPolicy();
            if (password != null && !password.isBlank()) {
                aclPolicy.configureRequirePass(password);
            }
            MetricsRegistry metrics = new MetricsRegistry();
            ServerConfig serverConfig = new ServerConfig(respHost, respPort, 10_000, 0, password,
                    ServerConfig.TlsConfig.disabled());
            TieringKvServer.RuntimeOptions serverOptions =
                    new TieringKvServer.RuntimeOptions(serverConfig.maxClients(), serverConfig.idleTimeoutSeconds());
            RuntimeConfigBridge runtimeConfig = RuntimeConfigBridge.builder()
                    .maxClients(serverOptions::maxClients, serverOptions::setMaxClients)
                    .timeout(serverOptions::idleTimeoutSeconds, serverOptions::setIdleTimeoutSeconds)
                    .requirePass(aclPolicy::requiresAuthForAnonymous, aclPolicy::configureRequirePass)
                    .build();
            MultiRaftStorageEngine finalStorage = storage;
            ServerInfoProvider serverInfo = new ServerInfoProvider(
                    metrics, null, finalStorage::size, respPort, ServerVersion.current());
            Map<String, Supplier<String>> sections = new LinkedHashMap<>(serverInfo.sections());
            sections.put("cluster", finalStorage::clusterInfoSection);
            Supplier<String> fullInfo = () -> serverInfo.render() + finalStorage.clusterInfoSection();
            shardExecutor = new KeyShardExecutor(
                    Math.max(2, Math.min(8, Runtime.getRuntime().availableProcessors())),
                    "cluster-resp-" + nodeId);
            CommandEngine engine = new CommandEngine(
                    CommandRegistry.createDefaultWithRuntime(
                            fullInfo, Map.copyOf(sections), null, null, null, aclPolicy, runtimeConfig),
                    finalStorage, shardExecutor, null, aclPolicy);
            respServer = new TieringKvServer(serverConfig, engine, metrics, serverOptions);
            respServer.start();
        }

        OpsLogger.startup("cluster-node", nodeId);
        System.out.printf("Tiering-KV cluster node %s listening on %d, groups=%s, respPort=%d, data=%s%n",
                nodeId, endpoint.boundPort(), groups,
                respServer == null ? -1 : respServer.boundPort(), dataDir);
        return new RunningClusterNode(nodeId, endpoint, manager, storage, shardExecutor, respServer);
    }

    public static final class RunningClusterNode implements AutoCloseable {
        private final String nodeId;
        private final MultiRaftEndpoint endpoint;
        private final RaftGroupManager manager;
        private final MultiRaftStorageEngine storage;
        private final KeyShardExecutor executor;
        private final TieringKvServer respServer;

        RunningClusterNode(String nodeId, MultiRaftEndpoint endpoint, RaftGroupManager manager,
                           MultiRaftStorageEngine storage, KeyShardExecutor executor, TieringKvServer respServer) {
            this.nodeId = nodeId;
            this.endpoint = endpoint;
            this.manager = manager;
            this.storage = storage;
            this.executor = executor;
            this.respServer = respServer;
        }

        public String nodeId() {
            return nodeId;
        }

        public int raftPort() {
            return endpoint.boundPort();
        }

        public int respPort() {
            return respServer == null ? -1 : respServer.boundPort();
        }

        public RaftGroupManager manager() {
            return manager;
        }

        public MultiRaftStorageEngine storage() {
            return storage;
        }

        @Override
        public void close() {
            if (respServer != null) {
                respServer.shutdown();
            }
            if (executor != null) {
                executor.close();
            }
            manager.close();
            endpoint.close();
        }
    }

    static Map<String, String> parse(String[] args) {
        Map<String, String> options = new HashMap<>();
        for (int i = 0; i + 1 < args.length; i += 2) {
            if (args[i].startsWith("--")) {
                options.put(args[i].substring(2), args[i + 1]);
            }
        }
        return options;
    }

    private static List<String> parseGroups(String raw) {
        List<String> groups = Arrays.stream(raw.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .toList();
        if (groups.isEmpty()) {
            throw new IllegalArgumentException("--groups must not be empty");
        }
        return groups;
    }

    private static Map<String, InetSocketAddress> deriveRespPeers(
            Map<String, InetSocketAddress> raftPeers, int respPort) {
        Map<String, InetSocketAddress> out = new LinkedHashMap<>();
        raftPeers.forEach((id, addr) -> out.put(id, InetSocketAddress.createUnresolved(
                addr.getHostString(), respPort)));
        return out;
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
            if (hostPort.length != 2) {
                throw new IllegalArgumentException("invalid peer address " + peer);
            }
            addresses.put(parts[0], InetSocketAddress.createUnresolved(
                    hostPort[0], Integer.parseInt(hostPort[1])));
        }
        return addresses;
    }
}
