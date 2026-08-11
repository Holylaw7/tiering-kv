package io.tieringkv.txn.meta;

import io.tieringkv.transaction.lifecycle.TxnLifecycleState;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据快照字节序列化（ADR-0099）：Raft SnapshotManager 状态机复用。 */
class MetadataSnapshotByteTest {

    @ParameterizedTest(name = "entry {0}")
    @EnumSource(TxnMetaEntry.State.class)
    void entryStateByteRoundTrip(TxnMetaEntry.State target) throws Exception {
        TransactionMetadataState state = stateWith(target, 42, 7,
                new byte[]{1}, Map.of());
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(state));
        TxnMetaEntry entry = restored.get("t1");
        assertThat(entry.state()).isEqualTo(target);
        assertThat(entry.commitTS()).isEqualTo(
                target == TxnMetaEntry.State.PREPARED
                        || target == TxnMetaEntry.State.COMMITTED ? 42 : 0);
        assertThat(entry.decisionIndex()).isEqualTo(7);
    }

    @ParameterizedTest(name = "lifecycle {0}")
    @EnumSource(TxnLifecycleState.class)
    void lifecycleByteRoundTrip(TxnLifecycleState state) throws Exception {
        TransactionMetadataState source = new TransactionMetadataState();
        source.apply(TxnMetaCommand.lifecycle("t1", 1, state.name(),
                12_345));
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(source));
        assertThat(restored.lifecycleSnapshot().get("t1").state())
                .isEqualTo(state);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 5, 25, 100, 500})
    void parameterizedTxnByteRoundTrip(int count) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < count; i++) {
            applyEntry(state, "t" + i,
                    TxnMetaEntry.State.values()[i % 4], i);
        }
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(state));
        assertThat(restored.size()).isEqualTo(count);
        for (int i = 0; i < count; i++) {
            assertThat(restored.get("t" + i).state())
                    .isEqualTo(TxnMetaEntry.State.values()[i % 4]);
        }
    }

    @ParameterizedTest(name = "lifecycle {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedLifecycleByteRoundTrip(int count) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < count; i++) {
            state.apply(TxnMetaCommand.lifecycle("t" + i, i,
                    TxnLifecycleState.ACTIVE.name(), 1000 + i));
        }
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(state));
        assertThat(restored.lifecycleSnapshot()).hasSize(count);
    }

    @ParameterizedTest(name = "mutations {0}")
    @ValueSource(ints = {0, 1, 4, 16, 64})
    void parameterizedMutationByteRoundTrip(int mutationCount)
            throws Exception {
        TransactionMetadataState state = stateWith(
                TxnMetaEntry.State.PREPARED, 9, -1, new byte[]{1},
                mutations(mutationCount));
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(state));
        Map<String, List<TxnMessages.Mutation>> regions =
                restored.get("t1").regionMutations();
        if (mutationCount == 0) {
            assertThat(regions).isEmpty();
            return;
        }
        List<TxnMessages.Mutation> list = regions.get("r1");
        assertThat(list).hasSize(mutationCount);
        for (int i = 0; i < mutationCount; i++) {
            assertThat(list.get(i).key()).isEqualTo(
                    ("k" + i).getBytes());
            assertThat(list.get(i).value()).isEqualTo(
                    ("v" + i).getBytes());
        }
    }

    @ParameterizedTest(name = "primary {0}")
    @ValueSource(ints = {0, 1, 256, 4096})
    void parameterizedPrimaryByteRoundTrip(int length) throws Exception {
        TransactionMetadataState state = stateWith(
                TxnMetaEntry.State.REGISTERED, 0, -1, new byte[length],
                Map.of());
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(state));
        assertThat(restored.get("t1").primary()).hasSize(length);
    }

    @Test
    void emptyByteRoundTrip() throws Exception {
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored,
                MetadataSnapshotManager.serialize(
                        new TransactionMetadataState()));
        assertThat(restored.size()).isZero();
        assertThat(restored.lifecycleSnapshot()).isEmpty();
    }

    @Test
    void truncatedBytePayloadTolerated() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < 10; i++) {
            applyEntry(state, "t" + i, TxnMetaEntry.State.REGISTERED, i);
        }
        byte[] payload = MetadataSnapshotManager.serialize(state);
        byte[] truncated = java.util.Arrays.copyOf(payload,
                payload.length - 5);
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored, truncated);
        assertThat(restored.size()).isLessThanOrEqualTo(10);
    }

    @Test
    void corruptedLengthBytePayloadTolerated() throws Exception {
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(11);
        buffer.putInt(1).putInt(100_000).put(new byte[]{1, 2, 3});
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored, buffer.array());
        assertThat(restored.size()).isZero();
    }

    @Test
    void loadIntoReplacesPreviousState() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "old", TxnMetaEntry.State.COMMITTED, 1);
        TransactionMetadataState fresh = new TransactionMetadataState();
        applyEntry(fresh, "new", TxnMetaEntry.State.PREPARED, 2);
        TransactionMetadataState target = new TransactionMetadataState();
        applyEntry(target, "stale", TxnMetaEntry.State.REGISTERED, 0);
        MetadataSnapshotManager.loadInto(target,
                MetadataSnapshotManager.serialize(state));
        assertThat(target.get("old")).isNotNull();
        assertThat(target.get("stale")).isNull();
        MetadataSnapshotManager.loadInto(target,
                MetadataSnapshotManager.serialize(fresh));
        assertThat(target.get("new")).isNotNull();
        assertThat(target.get("old")).isNull();
    }

    @Test
    void serializeDoesNotMutateSource() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "t1", TxnMetaEntry.State.PREPARED, 5);
        state.apply(TxnMetaCommand.lifecycle("t1", 1,
                TxnLifecycleState.ACTIVE.name(), 1000));
        MetadataSnapshotManager.serialize(state);
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.lifecycleSnapshot()).hasSize(1);
    }

    @Test
    void largePayloadRoundTrip() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < 200; i++) {
            state.apply(TxnMetaCommand.register("t" + i, new byte[1024],
                    i, Map.of("r1", List.of())));
        }
        byte[] payload = MetadataSnapshotManager.serialize(state);
        assertThat(payload.length).isGreaterThan(200 * 1024);
        TransactionMetadataState restored = new TransactionMetadataState();
        MetadataSnapshotManager.loadInto(restored, payload);
        assertThat(restored.size()).isEqualTo(200);
    }

    private static TransactionMetadataState stateWith(
            TxnMetaEntry.State target, long commitTS, long decisionIndex,
            byte[] primary,
            Map<String, List<TxnMessages.Mutation>> regions) {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "t1", target, decisionIndex, commitTS, primary,
                regions);
        return state;
    }

    private static void applyEntry(TransactionMetadataState state,
                                   String txnId, TxnMetaEntry.State target,
                                   long decisionIndex) {
        applyEntry(state, txnId, target, decisionIndex, 42, new byte[]{1},
                Map.of());
    }

    private static void applyEntry(TransactionMetadataState state,
                                   String txnId, TxnMetaEntry.State target,
                                   long decisionIndex, long commitTS,
                                   byte[] primary,
                                   Map<String, List<TxnMessages.Mutation>>
                                           regions) {
        state.apply(new TxnMetaCommand(TxnMetaCommand.Type.REGISTER, txnId,
                primary, 1, 0, decisionIndex, null, -1, regions));
        if (target == TxnMetaEntry.State.PREPARED
                || target == TxnMetaEntry.State.COMMITTED) {
            state.apply(new TxnMetaCommand(TxnMetaCommand.Type.PREPARE,
                    txnId, null, 0, commitTS, decisionIndex, null, -1,
                    Map.of()));
        }
        if (target == TxnMetaEntry.State.COMMITTED) {
            state.apply(new TxnMetaCommand(TxnMetaCommand.Type.COMMIT,
                    txnId, null, 0, commitTS, decisionIndex, null, -1,
                    Map.of()));
        }
        if (target == TxnMetaEntry.State.ROLLED_BACK) {
            state.apply(new TxnMetaCommand(TxnMetaCommand.Type.ROLLBACK,
                    txnId, null, 0, 0, decisionIndex, null, -1, Map.of()));
        }
    }

    private static Map<String, List<TxnMessages.Mutation>> mutations(
            int count) {
        if (count == 0) {
            return Map.of();
        }
        Map<String, List<TxnMessages.Mutation>> regions = new LinkedHashMap<>();
        List<TxnMessages.Mutation> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new TxnMessages.Mutation(
                    ("k" + i).getBytes(), ("v" + i).getBytes(), false));
        }
        regions.put("r1", list);
        return regions;
    }
}
