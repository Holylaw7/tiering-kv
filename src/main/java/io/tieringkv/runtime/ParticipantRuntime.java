package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.rpc.TxnParticipantRpcHandler;

import java.net.InetSocketAddress;
import java.util.Map;
import java.util.concurrent.CountDownLatch;

/** Participant 运行时（ADR-0093）：托管一个 Region 的 participant。 */
public final class ParticipantRuntime {

    private ParticipantRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        String nodeId = TxnRuntimeMain.require(options, "node-id");
        String regionId = TxnRuntimeMain.require(options, "region-id");
        int rpcPort = TxnRuntimeMain.port(options, "rpc-port", 7100);
        String dataDir = options.getOrDefault("data-dir", "/data");
        MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, rpcPort,
                Map.of(nodeId, new InetSocketAddress("0.0.0.0", rpcPort)));
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        TransactionParticipant participant = new TransactionParticipant(
                regionId, engine, new LockTable(), 60_000);
        endpoint.registerTxnHandler(regionId,
                new TxnParticipantRpcHandler(participant));
        endpoint.start();
        System.out.printf("ParticipantRuntime %s region=%s listening on %d "
                + "data=%s%n", nodeId, regionId, endpoint.boundPort(),
                dataDir);
        new CountDownLatch(1).await();
    }
}
