package io.tieringkv.txn.meta;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 网络化元数据 Raft 共享夹具（ADR-0099）：TCP 三/五节点组。 */
public final class MetadataNetworkFixture implements AutoCloseable {

    public static final String GROUP = "txn-meta";

    public final List<String> nodeIds;
    public final List<MultiRaftEndpoint> endpoints;
    public final List<TxnMetadataNode> nodes;
    public final MultiRaftEndpoint clientEndpoint;
    public final TxnMetadataClient client;
    public final Path dataRoot;
    public final Map<String, InetSocketAddress> addresses;
    public final List<Integer> ports;

    private MetadataNetworkFixture(List<String> nodeIds,
                                   List<MultiRaftEndpoint> endpoints,
                                   List<TxnMetadataNode> nodes,
                                   MultiRaftEndpoint clientEndpoint,
                                   TxnMetadataClient client,
                                   Path dataRoot,
                                   Map<String, InetSocketAddress> addresses,
                                   List<Integer> ports) {
        this.nodeIds = nodeIds;
        this.endpoints = endpoints;
        this.nodes = nodes;
        this.clientEndpoint = clientEndpoint;
        this.client = client;
        this.dataRoot = dataRoot;
        this.addresses = addresses;
        this.ports = ports;
    }

    public static MetadataNetworkFixture start(int count, Path dir)
            throws Exception {
        List<String> nodeIds = new ArrayList<>();
        Map<String, InetSocketAddress> addresses = new LinkedHashMap<>();
        for (int i = 0; i < count; i++) {
            String id = "meta-" + i;
            nodeIds.add(id);
            addresses.put(id, new InetSocketAddress("127.0.0.1", freePort()));
        }
        Path dataRoot = dir.resolve("cluster-" + System.nanoTime());
        List<MultiRaftEndpoint> endpoints = new ArrayList<>();
        List<TxnMetadataNode> nodes = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            MultiRaftEndpoint endpoint = new MultiRaftEndpoint(
                    nodeIds.get(i), addresses.get(nodeIds.get(i)).getPort(),
                    addresses);
            TxnMetadataNode node = new TxnMetadataNode(nodeIds.get(i),
                    GROUP, endpoint, dataRoot);
            endpoints.add(endpoint);
            nodes.add(node);
            endpoint.start();
            node.start();
            ports.add(endpoint.boundPort());
        }
        MultiRaftEndpoint clientEndpoint = new MultiRaftEndpoint("client",
                freePort(), addresses);
        clientEndpoint.start();
        TxnMetadataClient client = new TxnMetadataClient(clientEndpoint,
                GROUP, nodeIds);
        return new MetadataNetworkFixture(nodeIds, endpoints, nodes,
                clientEndpoint, client, dataRoot, addresses, ports);
    }

    /** 数据目录复用启动（ADR-0099）：同地址/端口重启整个组。 */
    public static MetadataNetworkFixture startWithData(
            List<String> nodeIds,
            Map<String, InetSocketAddress> addresses,
            Path dataRoot) throws Exception {
        Thread.sleep(1_000); // 等待端口释放
        List<MultiRaftEndpoint> endpoints = new ArrayList<>();
        List<TxnMetadataNode> nodes = new ArrayList<>();
        List<Integer> ports = new ArrayList<>();
        for (int i = 0; i < nodeIds.size(); i++) {
            MultiRaftEndpoint endpoint = new MultiRaftEndpoint(
                    nodeIds.get(i), addresses.get(nodeIds.get(i)).getPort(),
                    addresses);
            TxnMetadataNode node = new TxnMetadataNode(nodeIds.get(i),
                    GROUP, endpoint, dataRoot);
            endpoints.add(endpoint);
            nodes.add(node);
            endpoint.start();
            node.start();
            ports.add(endpoint.boundPort());
        }
        MultiRaftEndpoint clientEndpoint = new MultiRaftEndpoint("client",
                freePort(), addresses);
        clientEndpoint.start();
        TxnMetadataClient client = new TxnMetadataClient(clientEndpoint,
                GROUP, nodeIds);
        return new MetadataNetworkFixture(nodeIds, endpoints, nodes,
                clientEndpoint, client, dataRoot, addresses, ports);
    }

    public String leaderId() {
        return client.leaderId();
    }

    public void awaitLeader() throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                client.leaderId();
                return;
            } catch (IllegalStateException ignored) {
                Thread.sleep(20);
            }
        }
        throw new AssertionError("no metadata leader over network");
    }

    public String awaitNewLeader(String previous) throws Exception {
        long deadline = System.currentTimeMillis() + 8_000;
        while (System.currentTimeMillis() < deadline) {
            try {
                String candidate = client.leaderId();
                if (!candidate.equals(previous)) {
                    return candidate;
                }
            } catch (IllegalStateException ignored) {
                // 选举中
            }
            Thread.sleep(20);
        }
        throw new AssertionError("failover did not complete");
    }

    /** 关闭并同端口重启指定节点（数据目录保留）。 */
    public TxnMetadataNode restartNode(int index) throws Exception {
        int port = ports.get(index);
        try {
            nodes.get(index).close();
        } catch (RuntimeException ignored) {
            // 已关闭
        }
        try {
            endpoints.get(index).close();
        } catch (RuntimeException ignored) {
            // 已关闭
        }
        Thread.sleep(1_000);
        MultiRaftEndpoint restartedEndpoint = new MultiRaftEndpoint(
                nodeIds.get(index), port, addresses);
        TxnMetadataNode restarted = new TxnMetadataNode(nodeIds.get(index),
                GROUP, restartedEndpoint, dataRoot);
        restartedEndpoint.start();
        restarted.start();
        endpoints.set(index, restartedEndpoint);
        nodes.set(index, restarted);
        return restarted;
    }

    public int leaderIndex() {
        return nodeIds.indexOf(leaderId());
    }

    @Override
    public void close() {
        clientEndpoint.close();
        for (TxnMetadataNode node : nodes) {
            node.close();
        }
        for (MultiRaftEndpoint endpoint : endpoints) {
            endpoint.close();
        }
    }

    static int freePort() throws IOException {
        return io.tieringkv.testkit.TestPorts.freePort();
    }
}
