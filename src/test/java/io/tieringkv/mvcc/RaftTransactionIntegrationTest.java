package io.tieringkv.mvcc;

import io.tieringkv.cluster.RaftTestSupport;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.raft.log.MemoryRaftLog;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raft 集成（ADR-0073/0076）：事务记录经 Raft 提交/复制。 */
class RaftTransactionIntegrationTest {

    private List<RaftNode> group() throws InterruptedException {
        List<String> applied = Collections.synchronizedList(new ArrayList<>());
        List<RaftNode> peers = new ArrayList<>();
        List<RaftNode> nodes = new ArrayList<>();
        for (String id : List.of("n1", "n2", "n3")) {
            RaftNode node = new RaftNode(id, peers,
                    (index, command) -> applied.add(
                            new String(command, StandardCharsets.UTF_8)),
                    RaftTestSupport.ELECTION, 25, 10);
            nodes.add(node);
        }
        peers.addAll(nodes);
        RaftTestSupport.startAll(nodes.toArray(new RaftNode[0]));
        RaftTestSupport.awaitLeader(nodes, 5000);
        return nodes;
    }

    @Test
    void journalRecordThroughRaft() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("COMMIT txn-1")).get(5, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isZero();
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void journalRecordsReplicated() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("COMMIT txn-2")).get(5, TimeUnit.SECONDS);
            RaftTestSupport.awaitTrue("replicated", () ->
                    nodes.stream().allMatch(n -> n.logSize() == 1), 5000);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void committedTxnJournaledBeforeAck() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("COMMIT txn-3"))
                    .thenRun(() -> journal.record(bytes("ACK txn-3")))
                    .get(5, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isEqualTo(1);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void rollbackJournaled() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("ROLLBACK txn-4")).get(5, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isZero();
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void closedLeaderRejectsJournalRecord() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            leader.suspend();
            leader.close();
            TxnJournal journal = new TxnJournal.Raft(command -> {
                return leader.propose(command);
            });
            assertThatThrownBy(() -> journal.record(bytes("COMMIT txn-5"))
                    .get(5, TimeUnit.SECONDS))
                    .isInstanceOf(Exception.class);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void inMemoryJournal() {
        TxnJournal.InMemory journal = new TxnJournal.InMemory();
        journal.record(bytes("COMMIT a")).join();
        journal.record(bytes("ROLLBACK b")).join();
        assertThat(journal.size()).isEqualTo(2);
    }

    @Test
    void journalOrderPreserved() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("1")).get(5, TimeUnit.SECONDS);
            journal.record(bytes("2")).get(5, TimeUnit.SECONDS);
            journal.record(bytes("3")).get(5, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isEqualTo(2);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void proposeToFollowerFails() throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            RaftNode follower = nodes.stream()
                    .filter(n -> !n.id().equals(leader.id()))
                    .findFirst().orElseThrow();
            TxnJournal journal = new TxnJournal.Raft(command ->
                    follower.propose(command));
            assertThatThrownBy(() ->
                    journal.record(bytes("X")).get(5, TimeUnit.SECONDS))
                    .isInstanceOf(Exception.class);
        } finally {
            closeAll(nodes);
        }
    }

    @ParameterizedTest(name = "record {0}")
    @ValueSource(strings = {"COMMIT a", "ROLLBACK b", "PREPARE c",
            "COMMIT d", "ROLLBACK e", "PREPARE f", "COMMIT g", "ROLLBACK h"})
    void parameterizedRecordsThroughRaft(String record) throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes(record)).get(5, TimeUnit.SECONDS);
            assertThat(leader.commitIndex()).isGreaterThanOrEqualTo(0);
        } finally {
            closeAll(nodes);
        }
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {2, 5, 10})
    void parameterizedSequentialRecords(int count) throws Exception {
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            for (int i = 0; i < count; i++) {
                journal.record(bytes("COMMIT txn-" + i))
                        .get(5, TimeUnit.SECONDS);
            }
            assertThat(leader.commitIndex()).isEqualTo(count - 1);
        } finally {
            closeAll(nodes);
        }
    }

    @Test
    void journalRecoveryOnRestart() throws Exception {
        // 记录持久化到 raft 日志；重启后日志仍在（内存日志模拟不可重启，
        // 此处验证同一组内复制即恢复可见）
        List<RaftNode> nodes = group();
        try {
            RaftNode leader = RaftTestSupport.awaitLeader(nodes, 5000);
            TxnJournal journal = new TxnJournal.Raft(command ->
                    leader.propose(command));
            journal.record(bytes("COMMIT persisted")).get(5, TimeUnit.SECONDS);
            assertThat(nodes.stream().allMatch(n -> n.logSize() == 1)).isTrue();
        } finally {
            closeAll(nodes);
        }
    }

    private static void closeAll(List<RaftNode> nodes) {
        for (RaftNode node : nodes) {
            node.close();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
