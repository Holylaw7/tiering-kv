package io.tieringkv.transaction;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

/** EXEC 事务日志（ADR-0291）：结果登记，供审计与回滚验证。 */
public final class ExecJournal {

    public enum Outcome {
        SUCCESS,
        ROLLED_BACK,
        FAILED_ROLLBACK
    }

    public record ExecRecord(long txnId, int commandCount,
                             Outcome outcome, long timestampMillis) {
    }

    private final List<ExecRecord> records =
            new CopyOnWriteArrayList<>();
    private final AtomicLong sequence = new AtomicLong();

    public long record(int commandCount, Outcome outcome) {
        long txnId = sequence.incrementAndGet();
        records.add(new ExecRecord(txnId, commandCount, outcome,
                System.currentTimeMillis()));
        return txnId;
    }

    public List<ExecRecord> records() {
        return List.copyOf(records);
    }

    public int size() {
        return records.size();
    }
}
