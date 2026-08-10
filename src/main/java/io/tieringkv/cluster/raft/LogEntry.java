package io.tieringkv.cluster.raft;

import java.util.Arrays;

/** Raft 日志条目（ADR-0037）。 */
public record LogEntry(long term, long index, byte[] command) {

    public LogEntry {
        command = command.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof LogEntry that
                && term == that.term
                && index == that.index
                && Arrays.equals(command, that.command);
    }

    @Override
    public int hashCode() {
        return 31 * (31 * Long.hashCode(term) + Long.hashCode(index)) + Arrays.hashCode(command);
    }
}
