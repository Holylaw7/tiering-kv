package io.tieringkv.cluster.gateway;

import io.tieringkv.mvcc.ByteKey;
import io.tieringkv.mvcc.HybridLogicalClock;
import io.tieringkv.mvcc.LockTable;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.SnapshotReader;
import io.tieringkv.mvcc.TimestampOracle;
import io.tieringkv.mvcc.Transaction;
import io.tieringkv.mvcc.TransactionCoordinator;
import io.tieringkv.mvcc.TransactionMetricsRegistry;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Redis 自动事务（ADR-0079）：GET=快照读，SET/DEL=单键事务，
 * MGET=一致快照，MSET=单事务多键（跨 shard 走协调器）。
 * 写 startTS 使用 TimestampOracle（保证 commitTS > startTS），
 * 读 readTS 以 HLC.now() 为基准，并上界到 oracle 已分配水位，
 * 保证已提交版本全部可见。
 */
public final class AutoTransactionExecutor {

    public record Participant(String regionId, MvccStorageEngine engine,
                              LockTable locks) {
    }

    private final TimestampOracle oracle;
    private final HybridLogicalClock clock;
    private final TransactionCoordinator coordinator;
    private final Function<ByteKey, Participant> participantOf;
    private final TransactionMetricsRegistry metrics;
    private final AtomicLong txnIds = new AtomicLong();

    public AutoTransactionExecutor(TimestampOracle oracle,
                                   HybridLogicalClock clock,
                                   TransactionCoordinator coordinator,
                                   Function<ByteKey, Participant> participantOf) {
        this(oracle, clock, coordinator, participantOf, null);
    }

    public AutoTransactionExecutor(TimestampOracle oracle,
                                   HybridLogicalClock clock,
                                   TransactionCoordinator coordinator,
                                   Function<ByteKey, Participant> participantOf,
                                   TransactionMetricsRegistry metrics) {
        this.oracle = oracle;
        this.clock = clock;
        this.coordinator = coordinator;
        this.participantOf = participantOf;
        this.metrics = metrics;
    }

    /** GET：readTS = HLC.now() 快照读（最新已提交可见）。 */
    public byte[] get(byte[] key) {
        long readTS = readTimestamp();
        Participant participant = participant(key);
        if (metrics != null) {
            metrics.recordRead();
        }
        return new SnapshotReader().get(participant.engine(), key, readTS);
    }

    /** SET：单键事务（BEGIN → prewrite → commit）。 */
    public void set(byte[] key, byte[] value) {
        Transaction txn = begin();
        txn.put(key, value);
        commit(txn, key);
    }

    /** DEL：单键事务删除。 */
    public boolean delete(byte[] key) {
        Transaction txn = begin();
        boolean existed = new SnapshotReader().get(
                participant(key).engine(), key, txn.startTS()) != null;
        txn.delete(key);
        commit(txn, key);
        return existed;
    }

    /** MGET：同一 readTS 的一致快照。 */
    public List<byte[]> mget(List<byte[]> keys) {
        long readTS = readTimestamp();
        SnapshotReader reader = new SnapshotReader();
        List<byte[]> values = new ArrayList<>(keys.size());
        for (byte[] key : keys) {
            if (metrics != null) {
                metrics.recordRead();
            }
            values.add(reader.get(participant(key).engine(), key, readTS));
        }
        return values;
    }

    /** MSET：单事务多键写入（跨 shard 由协调器 2PC）。 */
    public void mset(List<byte[]> pairs) {
        Transaction txn = begin();
        for (int i = 0; i < pairs.size(); i += 2) {
            txn.put(pairs.get(i), pairs.get(i + 1));
        }
        commit(txn, pairs.get(0));
    }

    private Transaction begin() {
        if (metrics != null) {
            metrics.recordBegin();
        }
        return new Transaction("gw-txn-" + txnIds.incrementAndGet(),
                oracle.nextTimestamp());
    }

    private void commit(Transaction txn, byte[] primary) {
        long t0 = System.nanoTime();
        try {
            List<TransactionCoordinator.Participant> participants =
                    new ArrayList<>();
            java.util.LinkedHashSet<ByteKey> keys = new java.util.LinkedHashSet<>();
            keys.addAll(txn.writeKeys());
            keys.addAll(txn.deleteKeys());
            for (ByteKey key : keys) {
                Participant participant = participantOf.apply(key);
                boolean exists = participants.stream().anyMatch(p ->
                        p.regionId().equals(participant.regionId()));
                if (!exists) {
                    participants.add(new TransactionCoordinator.Participant(
                            participant.regionId(), participant.engine(),
                            participant.locks()));
                }
            }
            coordinator.commit(txn, participants);
            if (metrics != null) {
                metrics.recordCommit(System.nanoTime() - t0);
            }
        } catch (RuntimeException e) {
            if (metrics != null) {
                metrics.recordRollback();
                metrics.recordConflict();
            }
            throw e;
        }
    }

    private Participant participant(byte[] key) {
        Participant participant = participantOf.apply(new ByteKey(key));
        if (participant == null) {
            throw new IllegalStateException("no mvcc participant for key");
        }
        return participant;
    }

    /**
     * HLC 与 TimestampOracle 独立推进，HLC.now() 可能略落后于 oracle 已分配
     * 的 commitTS；以 max(HLC, oracle.peek()) 作为读水位，保证已提交可见。
     */
    private long readTimestamp() {
        return Math.max(clock.now(), oracle.peek());
    }
}
