package io.tieringkv.runtime;

import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.cluster.rpc.RpcFrame;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.cluster.rpc.TxnRpcHandler;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TransactionMetadataService;

import java.net.InetSocketAddress;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;

/** Metadata 运行时（ADR-0093）：托管事务元数据（Raft 待网络化，TD-047）。 */
public final class MetadataRuntime {

    private MetadataRuntime() {
    }

    public static void start(Map<String, String> options) throws Exception {
        String nodeId = TxnRuntimeMain.require(options, "node-id");
        int rpcPort = TxnRuntimeMain.port(options, "rpc-port", 7300);
        String dataDir = options.getOrDefault("data-dir", "/data");
        Path log = Path.of(dataDir).resolve("txn-meta.log");
        TransactionMetadataService service = new TransactionMetadataService(
                command -> CompletableFuture.completedFuture(1L), log);
        MultiRaftEndpoint endpoint = new MultiRaftEndpoint(nodeId, rpcPort,
                Map.of(nodeId, new InetSocketAddress("0.0.0.0", rpcPort)));
        endpoint.registerTxnHandler("meta", new TxnRpcHandler() {
            @Override
            public RpcFrame handle(RpcFrame request, String groupId,
                                   byte[] payload) {
                if (request.type() != RpcMessageType.TXN_METADATA) {
                    throw new IllegalArgumentException(
                            "expected TXN_METADATA");
                }
                service.applyLocal(TxnMetaCodec.decode(payload));
                return new RpcFrame(request.requestId(),
                        RpcMessageType.TXN_METADATA_RESPONSE,
                        io.tieringkv.transaction.rpc.TxnRpcCodec
                                .encodeResponse(
                                        io.tieringkv.transaction.rpc
                                                .TxnMessages.Response.ok()));
            }
        });
        endpoint.start();
        System.out.printf("MetadataRuntime %s listening on %d data=%s%n",
                nodeId, endpoint.boundPort(), dataDir);
        new CountDownLatch(1).await();
    }
}
