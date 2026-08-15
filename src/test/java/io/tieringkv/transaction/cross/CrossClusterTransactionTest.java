package io.tieringkv.transaction.cross;

import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.cluster.rpc.MultiRaftEndpoint;
import io.tieringkv.replication.cross.ConflictResolver;
import io.tieringkv.replication.cross.CrossClusterReplicationChannel;
import io.tieringkv.replication.cross.LwwConflictResolver;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨集群 2PC（ADR-0339）：参与者/协调器/决策日志/双 endpoint E2E。 */
class CrossClusterTransactionTest {

    @TempDir
    Path dir;

    private MultiRaftEndpoint endpointA;
    private MultiRaftEndpoint endpointB;

    @AfterEach
    void tearDown() {
        if (endpointB != null) {
            endpointB.close();
        }
        if (endpointA != null) {
            endpointA.close();
        }
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }

    private static ChangeEvent prepare(long seq, String txnId,
                                       String region, String key,
                                       String value, long ts) {
        return new ChangeEvent(seq, ChangeEvent.EventType.TXN_PREPARE,
                bytes(key), bytes(value), false, txnId, region, ts);
    }

    private static ChangeEvent commit(long seq, String txnId,
                                      String region, String key,
                                      String value, long ts) {
        return new ChangeEvent(seq, ChangeEvent.EventType.TXN_COMMIT,
                bytes(key), bytes(value), false, txnId, region, ts);
    }

    private static TxnMessages.Mutation put(String key, String value) {
        return new TxnMessages.Mutation(bytes(key), bytes(value), false);
    }

    private static TxnMessages.Mutation del(String key) {
        return new TxnMessages.Mutation(bytes(key), null, true);
    }

    @Test
    void eventTypeOrdinalsFrozen() {
        assertThat(ChangeEvent.EventType.PUT.ordinal()).isZero();
        assertThat(ChangeEvent.EventType.DELETE.ordinal()).isEqualTo(1);
        assertThat(ChangeEvent.EventType.TXN_COMMIT.ordinal())
                .isEqualTo(2);
        assertThat(ChangeEvent.EventType.REGION_MOVE.ordinal())
                .isEqualTo(3);
        assertThat(ChangeEvent.EventType.TXN_PREPARE.ordinal())
                .isEqualTo(4);
        assertThat(ChangeEvent.EventType.TXN_ROLLBACK.ordinal())
                .isEqualTo(5);
    }

    @Test
    void prepareThenCommitAppliesMutations() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        assertThat(participant.onEvent(prepare(1, "t1", "c1",
                "k", "v", 100), "c1")).isTrue();
        assertThat(storage.get(bytes("k"))).isNull();
        assertThat(participant.pendingSize()).isEqualTo(1);
        assertThat(participant.onEvent(commit(1, "t1", "c1",
                "k", "v", 200), "c1")).isTrue();
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("v"));
        assertThat(participant.pendingSize()).isZero();
    }

    @Test
    void rollbackDiscardsStagedMutations() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        participant.onEvent(prepare(1, "t1", "c1", "k", "v", 100),
                "c1");
        ChangeEvent rollback = new ChangeEvent(1,
                ChangeEvent.EventType.TXN_ROLLBACK, bytes("k"),
                null, false, "t1", "c1", 0);
        assertThat(participant.onEvent(rollback, "c1")).isTrue();
        assertThat(storage.get(bytes("k"))).isNull();
        assertThat(participant.pendingSize()).isZero();
    }

    @Test
    void commitWithoutPrepareAppliesDirectly() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        assertThat(participant.onEvent(commit(1, "t1", "c1",
                "k", "v", 200), "c1")).isTrue();
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("v"));
    }

    @Test
    void commitReplayIsIdempotent() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        participant.onEvent(commit(1, "t1", "c1", "k", "v", 200),
                "c1");
        participant.onEvent(commit(1, "t1", "c1", "k", "v", 200),
                "c1");
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("v"));
    }

    @Test
    void conflictingCommitsConvergeByLww() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        participant.onEvent(prepare(1, "t1", "c1", "k", "old", 100),
                "c1");
        participant.onEvent(prepare(2, "t2", "c1", "k", "new", 101),
                "c1");
        participant.onEvent(commit(1, "t1", "c1", "k", "old", 200),
                "c1");
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("old"));
        participant.onEvent(commit(2, "t2", "c1", "k", "new", 150),
                "c1");
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("old"));
    }

    @Test
    void sameTimestampTieBreaksByClusterId() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        participant.onEvent(commit(1, "t1", "c1", "k", "a", 200),
                "c1");
        participant.onEvent(commit(2, "t2", "c2", "k", "b", 200),
                "c2");
        assertThat(storage.get(bytes("k")))
                .isEqualTo(bytes("b"));
    }

    @Test
    void deleteMutationAppliedOnCommit() {
        MemTable storage = MemTable.create();
        storage.put(bytes("k"), bytes("v"));
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        ChangeEvent prepareDel = new ChangeEvent(1,
                ChangeEvent.EventType.TXN_PREPARE, bytes("k"),
                null, true, "t1", "c1", 100);
        participant.onEvent(prepareDel, "c1");
        ChangeEvent commitDel = new ChangeEvent(1,
                ChangeEvent.EventType.TXN_COMMIT, bytes("k"),
                null, true, "t1", "c1", 200);
        participant.onEvent(commitDel, "c1");
        assertThat(storage.get(bytes("k"))).isNull();
    }

    @Test
    void decisionLogRoundTrip() throws Exception {
        CrossClusterDecisionLog log = CrossClusterDecisionLog.open(
                dir.resolve("decisions"));
        CrossClusterDecision decision = new CrossClusterDecision(
                "cc-1", CrossClusterDecision.Decision.COMMIT, 200,
                List.of(put("k1", "v1"), del("k2")));
        log.append(decision);
        CrossClusterDecisionLog reopened = CrossClusterDecisionLog.open(
                dir.resolve("decisions"));
        List<CrossClusterDecision> decisions = reopened.readAll();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).txnId()).isEqualTo("cc-1");
        assertThat(decisions.get(0).decision())
                .isEqualTo(CrossClusterDecision.Decision.COMMIT);
        assertThat(decisions.get(0).commitTS()).isEqualTo(200);
        assertThat(decisions.get(0).mutations()).hasSize(2);
        assertThat(decisions.get(0).mutations().get(1).deleted())
                .isTrue();
    }

    @Test
    void decisionLogToleratesCorruptedTail() throws Exception {
        CrossClusterDecisionLog log = CrossClusterDecisionLog.open(
                dir.resolve("decisions"));
        log.append(new CrossClusterDecision("cc-1",
                CrossClusterDecision.Decision.COMMIT, 100,
                List.of(put("k", "v"))));
        log.append(new CrossClusterDecision("cc-2",
                CrossClusterDecision.Decision.ROLLBACK, 0,
                List.of(put("k2", "v2"))));
        Path file = dir.resolve("decisions")
                .resolve("cross-cluster-decisions.log");
        byte[] raw = java.nio.file.Files.readAllBytes(file);
        raw[raw.length - 1] ^= 0x7F;
        java.nio.file.Files.write(file, raw);
        List<CrossClusterDecision> decisions =
                CrossClusterDecisionLog.open(dir.resolve("decisions"))
                        .readAll();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).txnId()).isEqualTo("cc-1");
    }

    private void startEndpoints() throws Exception {
        int portA = io.tieringkv.testkit.TestPorts.freePort();
        int portB = io.tieringkv.testkit.TestPorts.freePort();
        Map<String, InetSocketAddress> addresses = Map.of(
                "a", new InetSocketAddress("127.0.0.1", portA),
                "b", new InetSocketAddress("127.0.0.1", portB));
        endpointA = new MultiRaftEndpoint("a", portA, addresses);
        endpointB = new MultiRaftEndpoint("b", portB, addresses);
        endpointA.start();
        endpointB.start();
    }

    @Test
    void crossClusterCommitAppliesToBothClusters() throws Exception {
        startEndpoints();
        MemTable storageA = MemTable.create();
        MemTable storageB = MemTable.create();
        CrossClusterTxnParticipant participantA =
                new CrossClusterTxnParticipant(storageA,
                        new LwwConflictResolver());
        CrossClusterTxnParticipant participantB =
                new CrossClusterTxnParticipant(storageB,
                        new LwwConflictResolver());
        CrossClusterReplicationChannel receiverA =
                new CrossClusterReplicationChannel(endpointA, "b");
        CrossClusterReplicationChannel receiverB =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiverA.registerConsumer(event -> participantA.onEvent(
                event, "c1"));
        receiverB.registerConsumer(event -> participantB.onEvent(
                event, "c2"));
        CrossClusterReplicationChannel channelToA =
                new CrossClusterReplicationChannel(endpointA, "a");
        CrossClusterReplicationChannel channelToB =
                new CrossClusterReplicationChannel(endpointA, "b");

        CrossClusterTxnCoordinator coordinator =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(
                                dir.resolve("decisions")),
                        Map.of("c1", channelToA, "c2", channelToB),
                        key -> new String(key,
                                StandardCharsets.UTF_8)
                                .startsWith("2") ? "c2" : "c1");
        CrossClusterTxnCoordinator.CrossClusterTxn txn =
                coordinator.begin(List.of(
                        put("1:key", "v1"),
                        put("2:key", "v2")));
        coordinator.commit(txn);
        assertThat(storageA.get(bytes("1:key")))
                .isEqualTo(bytes("v1"));
        assertThat(storageB.get(bytes("2:key")))
                .isEqualTo(bytes("v2"));
        List<CrossClusterDecision> decisions =
                CrossClusterDecisionLog.open(dir.resolve("decisions"))
                        .readAll();
        assertThat(decisions).hasSize(1);
        assertThat(decisions.get(0).decision())
                .isEqualTo(CrossClusterDecision.Decision.COMMIT);
    }

    @Test
    void prepareFailureRollsBackAllClusters() throws Exception {
        startEndpoints();
        MemTable storageA = MemTable.create();
        MemTable storageB = MemTable.create();
        CrossClusterTxnParticipant participantA =
                new CrossClusterTxnParticipant(storageA,
                        new LwwConflictResolver());
        CrossClusterTxnParticipant participantB =
                new CrossClusterTxnParticipant(storageB,
                        new LwwConflictResolver());
        CrossClusterReplicationChannel receiverA =
                new CrossClusterReplicationChannel(endpointA, "b");
        CrossClusterReplicationChannel receiverB =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiverA.registerConsumer(event -> participantA.onEvent(
                event, "c1"));
        receiverB.registerConsumer(event -> {
            if (event.type() == ChangeEvent.EventType.TXN_PREPARE
                    && new String(event.key(),
                    StandardCharsets.UTF_8).startsWith("bad")) {
                throw new IllegalArgumentException("rejected");
            }
            participantB.onEvent(event, "c2");
        });
        CrossClusterReplicationChannel channelToA =
                new CrossClusterReplicationChannel(endpointA, "a");
        CrossClusterReplicationChannel channelToB =
                new CrossClusterReplicationChannel(endpointA, "b");
        CrossClusterTxnCoordinator coordinator =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(
                                dir.resolve("decisions")),
                        Map.of("c1", channelToA, "c2", channelToB),
                        key -> new String(key,
                                StandardCharsets.UTF_8)
                                .startsWith("bad") ? "c2" : "c1");
        CrossClusterTxnCoordinator.CrossClusterTxn txn =
                coordinator.begin(List.of(
                        put("ok", "v1"),
                        put("bad:key", "v2")));
        assertThatThrownBy(() -> coordinator.commit(txn))
                .isInstanceOf(IllegalStateException.class);
        assertThat(storageA.get(bytes("ok"))).isNull();
        assertThat(storageB.get(bytes("bad:key"))).isNull();
        List<CrossClusterDecision> decisions =
                CrossClusterDecisionLog.open(dir.resolve("decisions"))
                        .readAll();
        assertThat(decisions.get(0).decision())
                .isEqualTo(CrossClusterDecision.Decision.ROLLBACK);
    }

    @Test
    void recoverReplaysCommitIdempotently() throws Exception {
        startEndpoints();
        MemTable storageA = MemTable.create();
        MemTable storageB = MemTable.create();
        CrossClusterTxnParticipant participantA =
                new CrossClusterTxnParticipant(storageA,
                        new LwwConflictResolver());
        CrossClusterTxnParticipant participantB =
                new CrossClusterTxnParticipant(storageB,
                        new LwwConflictResolver());
        CrossClusterReplicationChannel receiverA =
                new CrossClusterReplicationChannel(endpointA, "b");
        CrossClusterReplicationChannel receiverB =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiverA.registerConsumer(event -> participantA.onEvent(
                event, "c1"));
        receiverB.registerConsumer(event -> participantB.onEvent(
                event, "c2"));
        CrossClusterReplicationChannel channelToA =
                new CrossClusterReplicationChannel(endpointA, "a");
        CrossClusterReplicationChannel channelToB =
                new CrossClusterReplicationChannel(endpointA, "b");
        Path logDir = dir.resolve("decisions");
        CrossClusterTxnCoordinator coordinator =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(logDir),
                        Map.of("c1", channelToA, "c2", channelToB),
                        key -> new String(key,
                                StandardCharsets.UTF_8)
                                .startsWith("2") ? "c2" : "c1");
        coordinator.commit(coordinator.begin(List.of(
                put("1:key", "v1"), put("2:key", "v2"))));

        CrossClusterTxnCoordinator restarted =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(logDir),
                        Map.of("c1", channelToA, "c2", channelToB),
                        key -> new String(key,
                                StandardCharsets.UTF_8)
                                .startsWith("2") ? "c2" : "c1");
        assertThat(restarted.recover()).isEqualTo(1);
        assertThat(storageA.get(bytes("1:key")))
                .isEqualTo(bytes("v1"));
        assertThat(storageB.get(bytes("2:key")))
                .isEqualTo(bytes("v2"));
    }

    @Test
    void sequentialTransactionsConvergeToLatest() throws Exception {
        startEndpoints();
        MemTable storageB = MemTable.create();
        CrossClusterTxnParticipant participantB =
                new CrossClusterTxnParticipant(storageB,
                        new LwwConflictResolver());
        CrossClusterReplicationChannel receiverB =
                new CrossClusterReplicationChannel(endpointB, "a");
        receiverB.registerConsumer(event -> participantB.onEvent(
                event, "c1"));
        CrossClusterReplicationChannel channelToB =
                new CrossClusterReplicationChannel(endpointA, "b");
        CrossClusterTxnCoordinator coordinator =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(
                                dir.resolve("decisions")),
                        Map.of("c1", channelToB),
                        key -> "c1");
        coordinator.commit(coordinator.begin(List.of(
                put("k", "first"))));
        coordinator.commit(coordinator.begin(List.of(
                put("k", "second"))));
        assertThat(storageB.get(bytes("k")))
                .isEqualTo(bytes("second"));
        assertThat(CrossClusterDecisionLog.open(
                dir.resolve("decisions")).readAll()).hasSize(2);
    }

    @Test
    void coordinatorRejectsUnknownCluster() throws Exception {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        CrossClusterTxnCoordinator coordinator =
                new CrossClusterTxnCoordinator(
                        CrossClusterDecisionLog.open(
                                dir.resolve("decisions")),
                        Map.of(),
                        key -> "missing");
        CrossClusterTxnCoordinator.CrossClusterTxn txn =
                coordinator.begin(List.of(put("k", "v")));
        assertThatThrownBy(() -> coordinator.commit(txn))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void participantIgnoresNonPhaseEvents() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        assertThat(participant.onEvent(prepare(1, "t1", "c1",
                "k", "v", 100), "c1")).isTrue();
        assertThat(participant.onEvent(new ChangeEvent(1,
                ChangeEvent.EventType.REGION_MOVE, bytes("k"),
                null, false, "t1", "c1", 100), "c1")).isFalse();
    }

    @Test
    void participantRejectsInvalidPrepare() {
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage,
                        new LwwConflictResolver());
        ChangeEvent invalid = new ChangeEvent(1,
                ChangeEvent.EventType.TXN_PREPARE, bytes("k"),
                null, false, "t1", "c1", 100);
        assertThat(participant.onEvent(invalid, "c1")).isFalse();
    }

    @Test
    void customConflictResolverPluggable() {
        ConflictResolver rejectAll = (event, origin) -> false;
        MemTable storage = MemTable.create();
        CrossClusterTxnParticipant participant =
                new CrossClusterTxnParticipant(storage, rejectAll);
        participant.onEvent(commit(1, "t1", "c1", "k", "v", 200),
                "c1");
        assertThat(storage.get(bytes("k"))).isNull();
    }
}
