package io.tieringkv.transaction;

import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.RaftState;
import io.tieringkv.transaction.metadata.TxnMetadataRaftGroup;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.List;
import java.util.concurrent.CompletableFuture;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据 Raft 组（ADR-0084）：选举、故障转移、日志收敛。 */
class MetadataLeaderFailoverTest {

    @Test
    void electsSingleLeader() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            int leaders = 0;
            for (RaftNode node : group.nodes()) {
                if (node.state() == RaftState.LEADER) {
                    leaders++;
                }
            }
            assertThat(leaders).isEqualTo(1);
        }
    }

    @Test
    void proposeViaLeaderApplied() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            CompletableFuture<Long> future =
                    group.proposer().apply(new byte[]{1, 2, 3});
            assertThat(future.join()).isGreaterThanOrEqualTo(0);
            RaftNode leader = group.leader();
            assertThat(leader.logSize()).isEqualTo(1);
        }
    }

    @Test
    void killLeaderNewLeaderElects() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            RaftNode leader = group.leader();
            leader.suspend();
            leader.close();
            RaftNode newLeader = TxnMetadataRaftGroup.awaitLeader(
                    group.nodes(), 8_000);
            assertThat(newLeader.id()).isNotEqualTo(leader.id());
        }
    }

    @Test
    void proposeAfterFailoverWorks() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            RaftNode leader = group.leader();
            leader.suspend();
            leader.close();
            RaftNode newLeader = TxnMetadataRaftGroup.awaitLeader(
                    group.nodes(), 8_000);
            assertThat(newLeader).isNotNull();
            CompletableFuture<Long> future =
                    group.proposer().apply(new byte[]{9});
            assertThat(future.join()).isGreaterThanOrEqualTo(0);
        }
    }

    @Test
    void logConvergesAfterFailover() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            group.proposer().apply(new byte[]{1}).join();
            RaftNode leader = group.leader();
            leader.suspend();
            leader.close();
            RaftNode newLeader = TxnMetadataRaftGroup.awaitLeader(
                    group.nodes(), 8_000);
            newLeader.propose(new byte[]{2}).join();
            Thread.sleep(300);
            List<RaftNode> active = group.nodes().stream()
                    .filter(RaftNode::active).toList();
            long max = active.stream()
                    .mapToLong(RaftNode::logSize).max().orElse(0);
            long min = active.stream()
                    .mapToLong(RaftNode::logSize).min().orElse(0);
            assertThat(min).isGreaterThanOrEqualTo(2);
            assertThat(max - min).isLessThanOrEqualTo(1);
        }
    }

    @Test
    void multipleProposalsReplicated() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            for (int i = 0; i < 10; i++) {
                group.proposer().apply(new byte[]{(byte) i}).join();
            }
            RaftNode leader = group.leader();
            assertThat(leader.logSize()).isEqualTo(10);
            Thread.sleep(200);
            for (RaftNode node : group.nodes()) {
                if (node.active()) {
                    assertThat(node.logSize()).isGreaterThanOrEqualTo(10);
                }
            }
        }
    }

    @Test
    void leaderReelectionNoDataLoss() throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            group.proposer().apply(new byte[]{1}).join();
            group.proposer().apply(new byte[]{2}).join();
            RaftNode first = group.leader();
            first.suspend();
            first.close();
            RaftNode second = TxnMetadataRaftGroup.awaitLeader(
                    group.nodes(), 8_000);
            second.propose(new byte[]{3}).join();
            assertThat(second.logSize()).isGreaterThanOrEqualTo(3);
        }
    }

    @Test
    void closeAllStopsSchedulers() throws Exception {
        TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3);
        group.proposer().apply(new byte[]{1}).join();
        group.close();
        for (RaftNode node : group.nodes()) {
            assertThat(node.active()).isFalse();
        }
    }

    @ParameterizedTest(name = "proposals {0}")
    @ValueSource(ints = {1, 3, 5, 10})
    void parameterizedProposalsReplicated(int proposalCount)
            throws Exception {
        try (TxnMetadataRaftGroup group = TxnMetadataRaftGroup.start(3)) {
            for (int i = 0; i < proposalCount; i++) {
                group.proposer().apply(new byte[]{(byte) i}).join();
            }
            Thread.sleep(200);
            for (RaftNode node : group.nodes()) {
                if (node.active()) {
                    assertThat(node.logSize())
                            .isGreaterThanOrEqualTo(proposalCount);
                }
            }
        }
    }
}
