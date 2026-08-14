package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.TransactionMetricsRegistry;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.metadata.TransactionMetadataService;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.router.DistributedTxnRouter;
import io.tieringkv.transaction.router.RegionTxnClient;
import io.tieringkv.transaction.router.RpcTxnTransport;
import io.tieringkv.transaction.router.TxnParticipantClient;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

/** Phase 24 CI E2E 共享夹具：TCP 全链路（coordinator + 2 participants + meta）。 */
public record Phase24E2EFixture(MultiRaftEndpoint coord,
                                MultiRaftEndpoint pa,
                                MultiRaftEndpoint pb,
                                MultiRaftEndpoint meta,
                                MvccStorageEngine engineA,
                                MvccStorageEngine engineB,
                                DistributedTxnRouter router,
                                RegionTxnClient regionA,
                                TimestampOracle oracle,
                                TransactionMetadataService metadata)
        implements AutoCloseable {

    public static Phase24E2EFixture start(Path dir) throws Exception {
        Map<String, InetSocketAddress> addresses = Map.of(
                "pa", new InetSocketAddress("127.0.0.1", freePort()),
                "pb", new InetSocketAddress("127.0.0.1", freePort()),
                "coord", new InetSocketAddress("127.0.0.1", freePort()),
                "meta", new InetSocketAddress("127.0.0.1", freePort()));
        MultiRaftEndpoint coord = new MultiRaftEndpoint("coord",
                addresses.get("coord").getPort(), addresses);
        MultiRaftEndpoint pa = new MultiRaftEndpoint("pa",
                addresses.get("pa").getPort(), addresses);
        MultiRaftEndpoint pb = new MultiRaftEndpoint("pb",
                addresses.get("pb").getPort(), addresses);
        MultiRaftEndpoint meta = new MultiRaftEndpoint("meta",
                addresses.get("meta").getPort(), addresses);
        coord.start();
        pa.start();
        pb.start();
        meta.start();
        MvccStorageEngine engineA = new MvccStorageEngine(MemTable.create());
        MvccStorageEngine engineB = new MvccStorageEngine(MemTable.create());
        pa.registerTxnHandler("r1", new TxnParticipantRpcHandler(
                new TransactionParticipant("r1", engineA,
                        new LockTable(), 60_000)));
        pb.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                new TransactionParticipant("r2", engineB,
                        new LockTable(), 60_000)));
        TransactionMetadataService metadata =
                new TransactionMetadataService(
                        command -> CompletableFuture.completedFuture(1L));
        RpcTxnTransport transport = new RpcTxnTransport(coord);
        RegionTxnClient regionA = new RegionTxnClient("r1",
                new TxnParticipantClient("pa", "r1", transport),
                key -> key.key().length > 0 && key.key()[0] == 'a');
        RegionTxnClient regionB = new RegionTxnClient("r2",
                new TxnParticipantClient("pb", "r2", transport),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        TimestampOracle oracle = new TimestampOracle();
        DistributedTxnRouter router = new DistributedTxnRouter(oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b'
                        ? regionB : regionA,
                List.of(regionA, regionB), metadata,
                new TransactionMetricsRegistry());
        return new Phase24E2EFixture(coord, pa, pb, meta, engineA, engineB,
                router, regionA, oracle, metadata);
    }

    public DistributedTxnRouter restartCoordinator() {
        RpcTxnTransport transport = new RpcTxnTransport(coord);
        RegionTxnClient regionA = new RegionTxnClient("r1",
                new TxnParticipantClient("pa", "r1", transport),
                key -> key.key().length > 0 && key.key()[0] == 'a');
        RegionTxnClient regionB = new RegionTxnClient("r2",
                new TxnParticipantClient("pb", "r2", transport),
                key -> key.key().length > 0 && key.key()[0] == 'b');
        return new DistributedTxnRouter(oracle,
                key -> key.key().length > 0 && key.key()[0] == 'b'
                        ? regionB : regionA,
                List.of(regionA, regionB), metadata,
                new TransactionMetricsRegistry());
    }

    public void restartParticipantB() throws Exception {
        int port = pb.boundPort();
        pb.close();
        awaitPortFree(port, 10_000);
        MultiRaftEndpoint restarted = new MultiRaftEndpoint("pb",
                port, Map.of("pb",
                new InetSocketAddress("127.0.0.1", port)));
        restarted.start();
        restarted.registerTxnHandler("r2", new TxnParticipantRpcHandler(
                new TransactionParticipant("r2", engineB,
                        new LockTable(), 60_000)));
    }

    /** 轮询等待端口释放，替代固定 sleep：慢 runner 上 1s 不足会 BindException。 */
    private static void awaitPortFree(int port, long timeoutMillis)
            throws InterruptedException {
        long deadline = System.nanoTime()
                + TimeUnit.MILLISECONDS.toNanos(timeoutMillis);
        while (System.nanoTime() < deadline) {
            try (ServerSocket probe = new ServerSocket()) {
                probe.setReuseAddress(true);
                probe.bind(new InetSocketAddress("127.0.0.1", port));
                return;
            } catch (IOException ignored) {
                Thread.sleep(100);
            }
        }
        throw new IllegalStateException(
                "port " + port + " still occupied after "
                        + timeoutMillis + "ms");
    }

    private static int freePort() throws IOException {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        }
    }

    @Override
    public void close() throws Exception {
        metadata.close();
        coord.close();
        pa.close();
        pb.close();
        meta.close();
        ((MemTable) engineA.underlying()).close();
        ((MemTable) engineB.underlying()).close();
    }
}
