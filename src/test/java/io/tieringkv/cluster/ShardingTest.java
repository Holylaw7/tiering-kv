package io.tieringkv.cluster;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.sharding.PartitionKey;
import io.tieringkv.cluster.sharding.SlotTable;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.HashSet;
import java.util.Set;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShardingTest {

    @Test
    void crc16KnownVector() {
        // CRC-16/XMODEM("123456789") = 0x31C3
        assertThat(HashSlotRouter.crc16("123456789".getBytes(StandardCharsets.US_ASCII)))
                .isEqualTo(0x31C3);
    }

    @Test
    void slotWithinRange() {
        for (int i = 0; i < 10_000; i++) {
            int slot = HashSlotRouter.slot(("key-" + i).getBytes(StandardCharsets.UTF_8));
            assertThat(slot).isBetween(0, 16_383);
        }
    }

    @Test
    void sameKeyAlwaysSameSlot() {
        byte[] key = "user:42".getBytes(StandardCharsets.UTF_8);
        int first = HashSlotRouter.slot(key);
        for (int i = 0; i < 1000; i++) {
            assertThat(HashSlotRouter.slot(key)).isEqualTo(first);
        }
    }

    @Test
    void distributionCoversManySlots() {
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < 100_000; i++) {
            slots.add(HashSlotRouter.slot(("bench:" + i).getBytes(StandardCharsets.UTF_8)));
        }
        assertThat(slots.size()).isGreaterThan(8000);
    }

    @Test
    void partitionKeySlotStable() {
        byte[] key = "k".getBytes(StandardCharsets.UTF_8);
        PartitionKey partitionKey = new PartitionKey(key);
        assertThat(partitionKey.slot()).isEqualTo(HashSlotRouter.slot(key));
        assertThat(partitionKey.key()).isEqualTo(key);
    }

    @Test
    void slotTableAssignsEvenly() {
        SlotTable table = new SlotTable();
        table.assignShards(3);
        assertThat(table.slotsOf(0)).hasSizeGreaterThan(5000);
        assertThat(table.slotsOf(1)).hasSizeGreaterThan(5000);
        assertThat(table.slotsOf(2)).hasSizeGreaterThan(5000);
    }

    @Test
    void shardForReturnsAssignedShard() {
        SlotTable table = new SlotTable();
        table.assignShards(4);
        assertThat(table.shardFor(0)).isZero();
        assertThat(table.shardFor(16_383)).isEqualTo(3);
    }

    @Test
    void reassignMovesSingleSlot() {
        SlotTable table = new SlotTable();
        table.assignShards(2);
        table.reassign(100, 1);
        assertThat(table.shardFor(100)).isEqualTo(1);
        assertThat(table.slotsOf(1)).contains(100);
    }

    @Test
    void unassignedSlotThrows() {
        SlotTable table = new SlotTable();
        assertThatThrownBy(() -> table.shardFor(5)).isInstanceOf(IllegalStateException.class);
    }

    @Test
    void invalidSlotRejected() {
        SlotTable table = new SlotTable();
        assertThatThrownBy(() -> table.shardFor(16_384))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
