package io.tieringkv.cluster.lifecycle.split;

import io.tieringkv.storage.memory.KeyValueEntry;

import java.util.List;
import java.util.zip.CRC32C;

/** 分裂快照（ADR-0061）：左/右子范围条目 + 屏障版本 + CRC。 */
public final class SplitSnapshot {

    private final List<KeyValueEntry> left;
    private final List<KeyValueEntry> right;
    private final long barrierVersion;
    private final long checksum;

    public SplitSnapshot(List<KeyValueEntry> left,
                         List<KeyValueEntry> right,
                         long barrierVersion) {
        this.left = List.copyOf(left);
        this.right = List.copyOf(right);
        this.barrierVersion = barrierVersion;
        CRC32C crc = new CRC32C();
        for (KeyValueEntry entry : left) {
            crc.update(entry.key());
            crc.update(entry.value() == null ? new byte[0] : entry.value());
        }
        for (KeyValueEntry entry : right) {
            crc.update(entry.key());
            crc.update(entry.value() == null ? new byte[0] : entry.value());
        }
        this.checksum = crc.getValue();
    }

    public List<KeyValueEntry> left() {
        return left;
    }

    public List<KeyValueEntry> right() {
        return right;
    }

    public long barrierVersion() {
        return barrierVersion;
    }

    public long checksum() {
        return checksum;
    }

    public int size() {
        return left.size() + right.size();
    }
}
