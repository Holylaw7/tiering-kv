package io.tieringkv.mvcc;

import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 跨 Region 2PC（ADR-0073）：全 participant 原子提交/回滚。 */
class CrossRegionTransactionTest {

    private final TimestampOracle oracle = new TimestampOracle();
    private final MvccStorageEngine regionA =
            new MvccStorageEngine(MemTable.create());
    private final MvccStorageEngine regionB =
            new MvccStorageEngine(MemTable.create());
    private final LockTable locksA = new LockTable();
    private final LockTable locksB = new LockTable();
    private final TransactionCoordinator coordinator =
            new TransactionCoordinator(oracle, 60_000);

    private TransactionCoordinator.Participant a() {
        return new TransactionCoordinator.Participant("a", regionA, locksA);
    }

    private TransactionCoordinator.Participant b() {
        return new TransactionCoordinator.Participant("b", regionB, locksB);
    }

    @Test
    void twoRegionCommit() {
        Transaction txn = new Transaction("txn-x", oracle.nextTimestamp());
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("b-key"), bytes("vb"));
        coordinator.commit(txn, List.of(a(), b()));
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        assertThat(regionA.latestValue(bytes("a-key"))).isEqualTo(bytes("va"));
        assertThat(regionB.latestValue(bytes("b-key"))).isEqualTo(bytes("vb"));
    }

    @Test
    void noPartialCommitOnFailure() {
        Transaction txn = new Transaction("txn-fail", 50);
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("b-key"), bytes("vb"));
        // B 已有更新版本 → B prewrite 失败 → 全部回滚
        regionB.putVersion(bytes("b-key"), bytes("newer"), 1, 100, WriteType.PUT);
        Transaction bEarly = new Transaction("txn-b", 50);
        bEarly.put(bytes("b-key"), bytes("x"));
        assertThatThrownBy(() -> coordinator.commit(txn, List.of(a(), b())))
                .isInstanceOf(WriteConflictException.class);
        assertThat(txn.state()).isEqualTo(Transaction.State.ROLLED_BACK);
        assertThat(regionA.latestValue(bytes("a-key"))).isNull();
        assertThat(locksA.size()).isZero();
        assertThat(locksB.size()).isZero();
    }

    @Test
    void participantStatesExplicit() {
        Transaction txn = new Transaction("txn-s", oracle.nextTimestamp());
        txn.put(bytes("k1"), bytes("v1"));
        coordinator.commit(txn, List.of(a(), b()));
        assertThat(locksA.size()).isZero();
        assertThat(locksB.size()).isZero();
        assertThat(regionA.latestValue(bytes("k1"))).isEqualTo(bytes("v1"));
    }

    @Test
    void commitTsSharedAcrossParticipants() {
        Transaction txn = new Transaction("txn-ts", oracle.nextTimestamp());
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("b-key"), bytes("vb"));
        coordinator.commit(txn, List.of(a(), b()));
        assertThat(regionA.versions(bytes("a-key")).get(0).commitTS())
                .isEqualTo(regionB.versions(bytes("b-key")).get(0).commitTS());
    }

    @Test
    void rollbackCleansBothParticipants() {
        Transaction txn = new Transaction("txn-rb", 50);
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("b-key"), bytes("vb"));
        regionB.putVersion(bytes("b-key"), bytes("newer"), 1, 100, WriteType.PUT);
        assertThatThrownBy(() -> coordinator.commit(txn, List.of(a(), b())))
                .isInstanceOf(WriteConflictException.class);
        assertThat(regionA.versions(bytes("a-key"))).isEmpty();
        assertThat(regionB.versions(bytes("b-key")))
                .anyMatch(e -> e.commitTS() == 100);
    }

    @Test
    void threeParticipantCommit() {
        MvccStorageEngine regionC = new MvccStorageEngine(MemTable.create());
        LockTable locksC = new LockTable();
        Transaction txn = new Transaction("txn-3", oracle.nextTimestamp());
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("b-key"), bytes("vb"));
        txn.put(bytes("c-key"), bytes("vc"));
        coordinator.commit(txn, List.of(a(), b(),
                new TransactionCoordinator.Participant("c", regionC, locksC)));
        assertThat(regionC.latestValue(bytes("c-key"))).isEqualTo(bytes("vc"));
        assertThat(locksC.size()).isZero();
    }

    @Test
    void singleParticipantEquivalent() {
        Transaction txn = new Transaction("txn-1", oracle.nextTimestamp());
        txn.put(bytes("a-key"), bytes("va"));
        coordinator.commit(txn, List.of(a()));
        assertThat(regionA.latestValue(bytes("a-key"))).isEqualTo(bytes("va"));
    }

    @Test
    void emptyParticipantsCommit() {
        Transaction txn = new Transaction("txn-0", oracle.nextTimestamp());
        coordinator.commit(txn, List.of());
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
    }

    @ParameterizedTest(name = "keys {0}")
    @ValueSource(ints = {2, 4, 6, 8})
    void parameterizedCrossRegionKeys(int keyCount) {
        Transaction txn = new Transaction("txn-p", oracle.nextTimestamp());
        for (int i = 0; i < keyCount; i++) {
            txn.put(bytes("k" + i), bytes("v" + i));
        }
        coordinator.commit(txn, List.of(a(), b()));
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        for (int i = 0; i < keyCount; i++) {
            assertThat(i % 2 == 0
                    ? regionA.latestValue(bytes("k" + i))
                    : regionB.latestValue(bytes("k" + i)))
                    .isEqualTo(bytes("v" + i));
        }
    }

    @ParameterizedTest(name = "conflictRegion {0}")
    @ValueSource(strings = {"a", "b"})
    void parameterizedRollbackAllOnConflict(String conflictRegion) {
        MvccStorageEngine conflicting = conflictRegion.equals("a") ? regionA : regionB;
        conflicting.putVersion(bytes("ck"), bytes("newer"), 1, 100, WriteType.PUT);
        Transaction txn = new Transaction("txn-cr", 50);
        txn.put(bytes("a-key"), bytes("va"));
        txn.put(bytes("ck"), bytes("x"));
        assertThatThrownBy(() -> coordinator.commit(txn, List.of(a(), b())))
                .isInstanceOf(WriteConflictException.class);
        assertThat(regionA.latestValue(bytes("a-key"))).isNull();
        assertThat(regionB.versions(bytes("a-key"))).isEmpty();
        assertThat(locksA.size()).isZero();
        assertThat(locksB.size()).isZero();
    }

    @ParameterizedTest(name = "participants {0}")
    @ValueSource(ints = {1, 2, 3})
    void parameterizedParticipantCount(int count) {
        Transaction txn = new Transaction("txn-n", oracle.nextTimestamp());
        txn.put(bytes("k"), bytes("v"));
        List<TransactionCoordinator.Participant> participants = new java.util.ArrayList<>();
        for (int i = 0; i < count; i++) {
            MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
            participants.add(new TransactionCoordinator.Participant(
                    "r" + i, engine, new LockTable()));
        }
        coordinator.commit(txn, participants);
        assertThat(txn.state()).isEqualTo(Transaction.State.COMMITTED);
        for (TransactionCoordinator.Participant participant : participants) {
            assertThat(participant.engine().latestValue(bytes("k")))
                    .isEqualTo(bytes("v"));
        }
    }

    @Test
    void concurrentCrossRegionTxns() throws Exception {
        Thread a1 = new Thread(() -> {
            Transaction txn = new Transaction("t1", oracle.nextTimestamp());
            txn.put(bytes("a-key"), bytes("1"));
            coordinator.commit(txn, List.of(a(), b()));
        });
        Thread a2 = new Thread(() -> {
            Transaction txn = new Transaction("t2", oracle.nextTimestamp());
            txn.put(bytes("b-key"), bytes("2"));
            coordinator.commit(txn, List.of(a(), b()));
        });
        a1.start();
        a2.start();
        a1.join();
        a2.join();
        assertThat(regionA.latestValue(bytes("a-key"))).isEqualTo(bytes("1"));
        assertThat(regionB.latestValue(bytes("b-key"))).isEqualTo(bytes("2"));
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
