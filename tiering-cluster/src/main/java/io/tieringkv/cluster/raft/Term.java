package io.tieringkv.cluster.raft;

/** Raft 任期（ADR-0038）：单调递增。 */
public record Term(long value) implements Comparable<Term> {

    @Override
    public int compareTo(Term other) {
        return Long.compare(value, other.value());
    }
}
