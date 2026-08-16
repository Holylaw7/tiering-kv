package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.transaction.lifecycle.TransactionLifecycleManager;
import io.tieringkv.transaction.lifecycle.TxnTimeoutScheduler;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.RpcTxnTransport;
import io.tieringkv.transaction.router.TxnParticipantClient;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CountDownLatch;

/** 协调器运行时（ADR-0093）：Router + 远程 participant + 远程 metadata。 */
public final class CoordinatorRuntime {

    private CoordinatorRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        RuntimeCoordinator coordinator = RuntimeCoordinator.start(options);
        System.out.printf("CoordinatorRuntime %s ready on %d regions=%d%n",
                coordinator.nodeId(), coordinator.endpoint().boundPort(),
                coordinator.regions().size());
        new CountDownLatch(1).await();
    }

    /** 共享构建：gateway 与 coordinator 复用。 */
    public static final class RuntimeCoordinator {
        private final String nodeId;
        private final MultiRaftEndpoint endpoint;
        private final List<RegionTxnClient> regions;
        private final DistributedTxnRouter router;
        private final TransactionMetadataService metadata;
        private final TransactionMetricsRegistry metrics;
        private final TxnTimeoutScheduler scheduler;

        private RuntimeCoordinator(String nodeId, MultiRaftEndpoint endpoint,
                                   List<RegionTxnClient> regions,
                                   DistributedTxnRouter router,
                                   TransactionMetadataService metadata,
                                   TransactionMetricsRegistry metrics,
                                   TxnTimeoutScheduler scheduler) {
            this.nodeId = nodeId;
            this.endpoint = endpoint;
            this.regions = regions;
            this.router = router;
            this.metadata = metadata;
            this.metrics = metrics;
            this.scheduler = scheduler;
        }

        public static RuntimeCoordinator start(Map<String, String> options)
                throws Exception {
            String nodeId = TxnRuntimeMain.require(options, "node-id");
            int rpcPort = TxnRuntimeMain.port(options, "rpc-port", 7200);
            String metadataNode = TxnRuntimeMain.require(options,
                    "metadata-node");
            int metadataPort = TxnRuntimeMain.port(options,
                    "metadata-port", 7300);
            String regionsSpec = TxnRuntimeMain.require(options, "regions");
            // ADR-0343 真实 Runner 修正：地址表必须包含 metadata 与
            // 全部 region host，否则 callTxn 返回 unknown peer
            // （gateway/coordinator 的元数据与参与者 RPC 全部失败）。
            MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, rpcPort,
                    buildRpcAddresses(nodeId, rpcPort, metadataNode,
                            metadataPort, regionsSpec));
            endpoint.start();
            RpcTxnTransport transport = new RpcTxnTransport(endpoint);
            TimestampOracle oracle = new TimestampOracle();
            TransactionMetricsRegistry metrics =
                    new TransactionMetricsRegistry();
            // 远程元数据：TXN_METADATA RPC → 远端 apply，本地缓存
            TransactionMetadataService metadata =
                    new TransactionMetadataService(
                            payload -> endpoint.callTxn(metadataNode,
                                    "meta", RpcMessageType.TXN_METADATA,
                                    payload)
                                    .thenApply(
                                            CoordinatorRuntime
                                                    ::decodeMetadataDecision));
            List<RegionTxnClient> regions = new ArrayList<>();
            for (String spec : regionsSpec.split(",")) {
                String[] parts = spec.split("@");
                String regionId = parts[0];
                String[] hostPort = parts[1].split(":");
                RegionTxnClient region = new RegionTxnClient(regionId,
                        new TxnParticipantClient(hostPort[0], regionId,
                                transport), key -> true);
                regions.add(region);
            }
            TransactionLifecycleManager lifecycle =
                    new TransactionLifecycleManager(metadata);
            DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                    key -> regions.get(0), regions, metadata, metrics,
                    lifecycle, 60_000, 300_000);
            TxnTimeoutScheduler scheduler = new TxnTimeoutScheduler(
                    lifecycle, router, metrics);
            scheduler.start(1_000);
            return new RuntimeCoordinator(nodeId, endpoint, regions, router,
                    metadata, metrics, scheduler);
        }

        public String nodeId() {
            return nodeId;
        }

        public MultiRaftEndpoint endpoint() {
            return endpoint;
        }

        public List<RegionTxnClient> regions() {
            return regions;
        }

        public DistributedTxnRouter router() {
            return router;
        }

        public TransactionMetadataService metadata() {
            return metadata;
        }

        public TransactionMetricsRegistry metrics() {
            return metrics;
        }

        public void close() throws Exception {
            scheduler.close();
            metadata.close();
            endpoint.close();
        }
    }

    /**
     * 构建 RPC 地址表（ADR-0343）：自身监听地址 + metadata +
     * 每个 region 的 participant host（createUnresolved，连接时
     * 由 Docker DNS 解析，避免启动期 DNS 未就绪即失败）。
     */
    static Map<String, InetSocketAddress> buildRpcAddresses(
            String nodeId, int rpcPort, String metadataNode,
            int metadataPort, String regionsSpec) {
        Map<String, InetSocketAddress> addresses = new HashMap<>();
        addresses.put(nodeId, new InetSocketAddress("0.0.0.0", rpcPort));
        addresses.put(metadataNode, InetSocketAddress.createUnresolved(
                metadataNode, metadataPort));
        for (String spec : regionsSpec.split(",")) {
            String[] parts = spec.split("@");
            if (parts.length != 2) {
                throw new IllegalArgumentException(
                        "invalid region spec: " + spec);
            }
            String[] hostPort = parts[1].split(":");
            if (hostPort.length != 2) {
                throw new IllegalArgumentException(
                        "invalid region endpoint: " + parts[1]);
            }
            addresses.put(hostPort[0], InetSocketAddress.createUnresolved(
                    hostPort[0], Integer.parseInt(hostPort[1])));
        }
        return addresses;
    }

    /**
     * 元数据提案响应校验（ADR-0350 容器级演练发现）：RPC ERROR 帧
     * 必须失败而非视为成功，否则磁盘满/元数据故障下事务静默提交。
     */
    static long decodeMetadataDecision(RpcFrame frame) {
        if (frame.type() == RpcMessageType.ERROR) {
            String message = new String(frame.payload(),
                    StandardCharsets.UTF_8);
            throw new CompletionException(new IOException(
                    "metadata proposal failed: " + message));
        }
        return 1L;
    }
}
