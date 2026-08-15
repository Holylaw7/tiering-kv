package io.tieringkv.cdc;

import java.util.Arrays;

/** CDC 变更事件（ADR-0105）：序号化、可重放、幂等消费。 */
public record ChangeEvent(long seq, EventType type, byte[] key,
                          byte[] value, boolean deleted, String txnId,
                          String regionId, long timestamp) {

    public enum EventType {
        PUT,
        DELETE,
        TXN_COMMIT,
        REGION_MOVE,
        TXN_PREPARE,
        TXN_ROLLBACK
    }

    public ChangeEvent {
        key = key == null ? new byte[0] : key.clone();
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
        return other instanceof ChangeEvent that
                && seq == that.seq && type == that.type
                && deleted == that.deleted
                && Arrays.equals(key, that.key)
                && Arrays.equals(value, that.value)
                && java.util.Objects.equals(txnId, that.txnId)
                && java.util.Objects.equals(regionId, that.regionId)
                && timestamp == that.timestamp;
    }

    @Override
    public int hashCode() {
        return java.util.Objects.hash(seq, type, Arrays.hashCode(key),
                Arrays.hashCode(value), deleted, txnId, regionId,
                timestamp);
    }
}
