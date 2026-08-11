package io.tieringkv.backup.pitr;

import java.util.Arrays;

/** PITR 变更记录（ADR-0104）：一次已提交写入的完整语义。 */
public record PitrRecord(long seq, long startTS, long commitTS,
                         byte[] key, byte[] value, boolean deleted,
                         String txnId, String regionId) {

    public PitrRecord {
        key = key.clone();
        value = value == null ? null : value.clone();
    }

    @Override
    public byte[] key() {
        return key.clone();
    }

    @Override
    public byte[] value() {
        return value == null ? null : value.clone();
    }

    @Override
    public boolean equals(Object other) {
        return other instanceof PitrRecord that
                && seq == that.seq && startTS == that.startTS
                && commitTS == that.commitTS
                && deleted == that.deleted
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && java.util.Objects.equals(txnId, that.txnId)
                && java.util.Objects.equals(regionId, that.regionId);
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(seq, startTS, commitTS,
                Arrays.hashCode(key), Arrays.hashCode(value), deleted,
                txnId, regionId);
    }
}
