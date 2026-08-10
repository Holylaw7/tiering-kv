package io.tieringkv.cluster.raft;

import java.util.Locale;
import java.util.concurrent.atomic.LongAdder;

/** Raft 指标（Phase 17）：leader_transfer_total / election_total / proposal_latency。 */
public final class RaftMetricsRegistry {

    private final LongAdder leaderTransfers = new LongAdder();
    private final LongAdder elections = new LongAdder();
    private final LongAdder proposals = new LongAdder();
    private final LongAdder proposalLatencyNanos = new LongAdder();

    public void recordLeaderTransfer() {
        leaderTransfers.increment();
    }

    public void recordElection() {
        elections.increment();
    }

    public void recordProposalLatency(long latencyNanos) {
        proposalLatencyNanos.add(latencyNanos);
        proposals.increment();
    }

    public Snapshot snapshot() {
        long count = proposals.sum();
        double avgMs = count == 0 ? 0
                : proposalLatencyNanos.sum() / (double) count / 1_000_000.0;
        return new Snapshot(leaderTransfers.sum(), elections.sum(), avgMs);
    }

    public String sectionText() {
        Snapshot s = snapshot();
        return String.format(Locale.ROOT,
                "# Raft\r\n"
                        + "leader_transfer_total:%d\r\n"
                        + "election_total:%d\r\n"
                        + "proposal_latency_ms:%.3f\r\n",
                s.leaderTransferTotal(), s.electionTotal(), s.proposalLatencyMs());
    }

    public record Snapshot(long leaderTransferTotal,
                           long electionTotal,
                           double proposalLatencyMs) {
    }
}
