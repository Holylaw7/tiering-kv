package io.tieringkv.transaction.geo;

import io.tieringkv.transaction.participant.TransactionParticipant;
import io.tieringkv.transaction.rpc.TxnMessages;

import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/** 本地地域传输（ADR-0109）：进程内 participant，供测试/单地域部署。 */
public final class LocalGeoRpcTransport implements GeoRpcTransport {

    private final Map<String, TransactionParticipant> participants =
            new ConcurrentHashMap<>();

    public void register(String region, TransactionParticipant participant) {
        participants.put(region, participant);
    }

    @Override
    public CompletableFuture<TxnMessages.Response> prewrite(
            String region, TxnMessages.Prewrite request) {
        return CompletableFuture.completedFuture(
                participant(region).prewrite(request));
    }

    @Override
    public CompletableFuture<TxnMessages.Response> commit(
            String region, TxnMessages.Commit request) {
        return CompletableFuture.completedFuture(
                participant(region).commit(request));
    }

    @Override
    public CompletableFuture<TxnMessages.Response> rollback(
            String region, TxnMessages.Rollback request) {
        return CompletableFuture.completedFuture(
                participant(region).rollback(request));
    }

    private TransactionParticipant participant(String region) {
        TransactionParticipant participant = participants.get(region);
        if (participant == null) {
            throw new IllegalArgumentException("unknown region " + region);
        }
        return participant;
    }
}
