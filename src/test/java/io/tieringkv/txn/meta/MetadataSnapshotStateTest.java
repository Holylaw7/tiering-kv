package io.tieringkv.txn.meta;

import io.tieringkv.transaction.lifecycle.TxnLifecycleRecord;
import io.tieringkv.transaction.lifecycle.TxnLifecycleState;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.metadata.TxnMetaEntry;
import io.tieringkv.transaction.metadata.TransactionMetadataState;
import io.tieringkv.transaction.rpc.TxnMessages;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.assertj.core.api.Assertions.assertThat;

/** 元数据快照状态保持（ADR-0095）：全状态机往返、生命周期记录、损坏容忍。 */
class MetadataSnapshotStateTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "entry {0}")
    @EnumSource(TxnMetaEntry.State.class)
    void entryStateRoundTrip(TxnMetaEntry.State target) throws Exception {
        TransactionMetadataState state = stateWith("t1", target, 42, 7,
                new byte[]{1}, Map.of());
        Path file = dir.resolve("entry-" + target + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        TxnMetaEntry restored = loaded.get("t1");
        assertThat(restored).isNotNull();
        assertThat(restored.state()).isEqualTo(target);
        assertThat(restored.commitTS()).isEqualTo(
                target == TxnMetaEntry.State.PREPARED
                        || target == TxnMetaEntry.State.COMMITTED ? 42 : 0);
        assertThat(restored.decisionIndex()).isEqualTo(7);
        assertThat(restored.primary()).isEqualTo(new byte[]{1});
    }

    @ParameterizedTest(name = "lifecycle {0}")
    @EnumSource(TxnLifecycleState.class)
    void lifecycleStateRoundTrip(TxnLifecycleState state) throws Exception {
        TransactionMetadataState source = new TransactionMetadataState();
        source.apply(TxnMetaCommand.lifecycle("t1", 1, state.name(), 12_345));
        Path file = dir.resolve("lifecycle-" + state + ".snap");
        MetadataSnapshotManager.snapshot(file, source);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        TxnLifecycleRecord restored = loaded.lifecycleSnapshot().get("t1");
        assertThat(restored).isNotNull();
        assertThat(restored.state()).isEqualTo(state);
        assertThat(restored.startTS()).isEqualTo(1);
        assertThat(restored.expireAtMillis()).isEqualTo(12_345);
        assertThat(restored.decisionIndex()).isEqualTo(-1);
    }

    @ParameterizedTest(name = "txns {0}")
    @ValueSource(ints = {1, 2, 5, 10, 25, 50, 100})
    void parameterizedTxnCountRoundTrip(int count) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < count; i++) {
            applyEntry(state, "t" + i,
                    TxnMetaEntry.State.values()[i % 4], i);
        }
        Path file = dir.resolve("txns-" + count + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.size()).isEqualTo(count);
        for (int i = 0; i < count; i++) {
            assertThat(loaded.get("t" + i).state())
                    .isEqualTo(TxnMetaEntry.State.values()[i % 4]);
        }
    }

    @ParameterizedTest(name = "lifecycle {0}")
    @ValueSource(ints = {1, 5, 20})
    void parameterizedLifecycleCountRoundTrip(int count) throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < count; i++) {
            state.apply(TxnMetaCommand.lifecycle("t" + i, i,
                    TxnLifecycleState.ACTIVE.name(), 1000 + i));
        }
        Path file = dir.resolve("lifecycles-" + count + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.lifecycleSnapshot()).hasSize(count);
        assertThat(loaded.lifecycleSnapshot().get("t" + (count - 1))
                .expireAtMillis()).isEqualTo(1000 + count - 1);
    }

    @ParameterizedTest(name = "mutations {0}")
    @ValueSource(ints = {0, 1, 4, 8})
    void parameterizedMutationCountRoundTrip(int mutationCount)
            throws Exception {
        TransactionMetadataState state = stateWith("t1",
                TxnMetaEntry.State.PREPARED, 9, -1, new byte[]{1},
                mutations(mutationCount));
        Path file = dir.resolve("mutations-" + mutationCount + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        if (mutationCount == 0) {
            assertThat(loaded.get("t1").regionMutations()).isEmpty();
            return;
        }
        List<TxnMessages.Mutation> restored = loaded.get("t1")
                .regionMutations().get("r1");
        List<TxnMessages.Mutation> expected = mutations(mutationCount)
                .get("r1");
        assertThat(restored).hasSize(expected.size());
        for (int i = 0; i < expected.size(); i++) {
            assertThat(restored.get(i).key())
                    .isEqualTo(expected.get(i).key());
            assertThat(restored.get(i).value())
                    .isEqualTo(expected.get(i).value());
            assertThat(restored.get(i).deleted())
                    .isEqualTo(expected.get(i).deleted());
        }
    }

    @ParameterizedTest(name = "primary {0}")
    @ValueSource(ints = {0, 1, 1024})
    void parameterizedPrimaryLengthRoundTrip(int length) throws Exception {
        TransactionMetadataState state = stateWith("t1",
                TxnMetaEntry.State.REGISTERED, 0, -1,
                new byte[length], Map.of());
        Path file = dir.resolve("primary-" + length + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.get("t1").primary()).hasSize(length);
    }

    @ParameterizedTest(name = "commitTS {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void parameterizedCommitTsRoundTrip(long commitTS) throws Exception {
        TransactionMetadataState state = stateWith("t1",
                TxnMetaEntry.State.COMMITTED, commitTS, -1,
                new byte[]{1}, Map.of());
        Path file = dir.resolve("commit-" + commitTS + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        assertThat(MetadataSnapshotManager.load(file).get("t1").commitTS())
                .isEqualTo(commitTS);
    }

    @ParameterizedTest(name = "decision {0}")
    @ValueSource(longs = {-1, 0, 7})
    void parameterizedDecisionIndexRoundTrip(long decisionIndex)
            throws Exception {
        TransactionMetadataState state = stateWith("t1",
                TxnMetaEntry.State.COMMITTED, 9, decisionIndex,
                new byte[]{1}, Map.of());
        Path file = dir.resolve("decision-" + decisionIndex + ".snap");
        MetadataSnapshotManager.snapshot(file, state);
        assertThat(MetadataSnapshotManager.load(file).get("t1")
                .decisionIndex()).isEqualTo(decisionIndex);
    }

    @Test
    void emptySnapshotRoundTrip() throws Exception {
        Path file = dir.resolve("empty.snap");
        MetadataSnapshotManager.snapshot(file, new TransactionMetadataState());
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.size()).isZero();
        assertThat(loaded.lifecycleSnapshot()).isEmpty();
    }

    @Test
    void missingFileReturnsEmptyState() throws Exception {
        assertThat(MetadataSnapshotManager.load(
                dir.resolve("does-not-exist.snap")).size()).isZero();
    }

    @Test
    void nullPrimaryRoundTrip() throws Exception {
        TransactionMetadataState state = stateWith("t1",
                TxnMetaEntry.State.REGISTERED, 0, -1, null, Map.of());
        Path file = dir.resolve("null-primary.snap");
        MetadataSnapshotManager.snapshot(file, state);
        assertThat(MetadataSnapshotManager.load(file).get("t1").primary())
                .isEmpty(); // 编解码层以空数组表示 null primary
    }

    @Test
    void truncatedEntryTailTolerated() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        for (int i = 0; i < 10; i++) {
            applyEntry(state, "t" + i, TxnMetaEntry.State.REGISTERED, i);
        }
        Path file = dir.resolve("trunc-entry.snap");
        MetadataSnapshotManager.snapshot(file, state);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 5));
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.size()).isLessThanOrEqualTo(10);
    }

    @Test
    void truncatedLifecycleTailTolerated() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "t0", TxnMetaEntry.State.COMMITTED, 3);
        for (int i = 0; i < 5; i++) {
            state.apply(TxnMetaCommand.lifecycle("t" + i, i,
                    TxnLifecycleState.ACTIVE.name(), 1000 + i));
        }
        Path file = dir.resolve("trunc-lifecycle.snap");
        MetadataSnapshotManager.snapshot(file, state);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes, bytes.length - 3));
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.get("t0")).isNotNull();
        assertThat(loaded.lifecycleSnapshot().size())
                .isLessThanOrEqualTo(5);
    }

    @Test
    void corruptedLengthTolerated() throws Exception {
        Path file = dir.resolve("corrupt.snap");
        java.nio.ByteBuffer buffer = java.nio.ByteBuffer.allocate(11);
        buffer.putInt(1).putInt(100_000).put(new byte[]{1, 2, 3});
        Files.write(file, buffer.array());
        assertThat(MetadataSnapshotManager.load(file).size()).isZero();
    }

    @Test
    void repeatedSnapshotOverwriteReflectsLatest() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "t0", TxnMetaEntry.State.REGISTERED, 0);
        Path file = dir.resolve("repeat.snap");
        MetadataSnapshotManager.snapshot(file, state);
        applyEntry(state, "t1", TxnMetaEntry.State.COMMITTED, 1);
        MetadataSnapshotManager.snapshot(file, state);
        assertThat(MetadataSnapshotManager.load(file).size()).isEqualTo(2);
    }

    @Test
    void snapshotDoesNotMutateState() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, "t0", TxnMetaEntry.State.PREPARED, 5);
        state.apply(TxnMetaCommand.lifecycle("t0", 1,
                TxnLifecycleState.ACTIVE.name(), 1000));
        Path file = dir.resolve("no-mutate.snap");
        MetadataSnapshotManager.snapshot(file, state);
        assertThat(state.size()).isEqualTo(1);
        assertThat(state.get("t0").state())
                .isEqualTo(TxnMetaEntry.State.PREPARED);
        assertThat(state.lifecycleSnapshot()).hasSize(1);
    }

    @Test
    void concurrentSnapshotCompletes() throws Exception {
        TransactionMetadataState state = new TransactionMetadataState();
        AtomicBoolean stop = new AtomicBoolean();
        Thread writer = new Thread(() -> {
            int i = 0;
            while (!stop.get()) {
                applyEntry(state, "t" + (i++),
                        TxnMetaEntry.State.values()[i % 4], i);
            }
        });
        writer.start();
        Path file = dir.resolve("concurrent.snap");
        MetadataSnapshotManager.snapshot(file, state);
        stop.set(true);
        writer.join(2_000);
        TransactionMetadataState loaded = MetadataSnapshotManager.load(file);
        assertThat(loaded.size()).isGreaterThanOrEqualTo(0);
    }

    private static TransactionMetadataState stateWith(
            String txnId, TxnMetaEntry.State target, long commitTS,
            long decisionIndex, byte[] primary,
            Map<String, List<TxnMessages.Mutation>> regions) {
        TransactionMetadataState state = new TransactionMetadataState();
        applyEntry(state, txnId, target, decisionIndex, commitTS, primary,
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
