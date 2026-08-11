package io.tieringkv.transaction.rpc;

import java.util.List;

/** 分布式事务 RPC 消息（ADR-0083）：请求/响应/状态。 */
public final class TxnMessages {

    private TxnMessages() {
    }

    public enum ParticipantState {
        LOCKED,
        PREPARED,
        COMMITTED,
        ROLLED_BACK
    }

    public enum Status {
        OK,
        ALREADY,
        CONFLICT,
        ERROR
    }

    public record Mutation(byte[] key, byte[] value, boolean deleted) {
    }

    public record Prewrite(String txnId, long startTS, byte[] primary,
                           List<Mutation> mutations) {
    }

    public record Commit(String txnId, long startTS, long commitTS,
                         byte[] primary, List<Mutation> mutations) {
    }

    public record Rollback(String txnId, long startTS, byte[] primary) {
    }

    public record Heartbeat(String txnId, long startTS, long ttlMillis) {
    }

    public record Response(Status status, String message) {

        public boolean succeeded() {
            return status == Status.OK || status == Status.ALREADY;
        }

        public static Response ok() {
            return new Response(Status.OK, "");
        }

        public static Response already() {
            return new Response(Status.ALREADY, "already");
        }

        public static Response conflict(String message) {
            return new Response(Status.CONFLICT, message);
        }

        public static Response error(String message) {
            return new Response(Status.ERROR, message);
        }
    }
}
