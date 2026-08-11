package io.tieringkv.benchmark.mvcc;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.tieringkv.backup.pitr.CheckpointManager;
import io.tieringkv.backup.pitr.MvccPitrRecorder;
import io.tieringkv.backup.pitr.PitrRecord;
import io.tieringkv.backup.pitr.PitrWriteLog;
import io.tieringkv.backup.pitr.RestoreTimeline;
import io.tieringkv.cdc.CDCProducer;
import io.tieringkv.cdc.ChangeEvent;
import io.tieringkv.mvcc.MvccStorageEngine;
import io.tieringkv.mvcc.WriteType;
import io.tieringkv.mvcc.index.PersistentMvccIndex;
import io.tieringkv.operator.OperatorPlanner;
import io.tieringkv.operator.TieringKVClusterSpec;
import io.tieringkv.operator.TieringKVClusterStatus;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespEncoder;
import io.tieringkv.security.CredentialManager;
import io.tieringkv.security.Permission;
import io.tieringkv.security.Role;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;

/** Phase 26 最终基准（v1 发布）：PITR/CDC/Security/Protocol/Operator。 */
class Phase26BenchmarkTest {

    @TempDir
    Path dir;

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {100, 500, 1000})
    void pitrAppendThroughput(int count) throws Exception {
        PitrWriteLog log = PitrWriteLog.open(dir.resolve("pitr-" + count));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            log.append(new PitrRecord(i, i, i * 10,
                    ("k" + i).getBytes(StandardCharsets.UTF_8),
                    ("v" + i).getBytes(StandardCharsets.UTF_8),
                    false, "t" + i, "r1"));
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH PITR-APPEND %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(log.watermark()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "records {0}")
    @ValueSource(ints = {100, 500})
    void pitrRestoreLatency(int count) throws Exception {
        Path archive = dir.resolve("restore-arch-" + count);
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archive);
        for (int i = 0; i < count; i++) {
            recorder.putVersion(("k" + i).getBytes(),
                    ("v" + i).getBytes(), i, i * 10, WriteType.PUT);
        }
        Path ckpt = dir.resolve("restore-ckpt-" + count);
        CheckpointManager.save(ckpt, new CheckpointManager.Checkpoint(
                recorder.watermark(), 1_000,
                PersistentMvccIndex.snapshotBytes(engine)));
        long start = System.nanoTime();
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckpt, archive, Long.MAX_VALUE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH PITR-RESTORE %d -> %d ms%n",
                count, elapsedMs);
        assertThat(restored.latestValue(("k" + (count - 1)).getBytes()))
                .isEqualTo(("v" + (count - 1)).getBytes());
    }

    @ParameterizedTest(name = "events {0}")
    @ValueSource(ints = {200, 800})
    void cdcAppendThroughput(int count) throws Exception {
        CDCProducer producer = new CDCProducer(dir.resolve("cdc-" + count));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            producer.emit(ChangeEvent.EventType.PUT,
                    ("k" + i).getBytes(), ("v" + i).getBytes(), false,
                    "t" + i, "r1");
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH CDC-APPEND %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(producer.watermark()).isEqualTo(count - 1);
    }

    @ParameterizedTest(name = "validations {0}")
    @ValueSource(ints = {1_000, 10_000})
    void securityValidateThroughput(int count) {
        CredentialManager manager = new CredentialManager();
        String token = manager.issue(Role.ADMIN, 60_000);
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            manager.require(token, Permission.READ);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH SECURITY %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
        assertThat(manager.validate(token)).isEqualTo(Role.ADMIN);
    }

    @ParameterizedTest(name = "ops {0}")
    @ValueSource(ints = {1_000, 10_000})
    void respEncodeThroughput(int count) {
        RespArray request = new RespArray(List.of(
                new RespBulkString("GET".getBytes()),
                new RespBulkString("k".getBytes())));
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            ByteBuf buffer = Unpooled.buffer();
            RespEncoder.write(buffer, request);
            buffer.release();
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH RESP-ENCODE %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "plans {0}")
    @ValueSource(ints = {1_000, 10_000})
    void operatorPlanThroughput(int count) {
        OperatorPlanner planner = new OperatorPlanner();
        TieringKVClusterSpec spec = new TieringKVClusterSpec(3, 3,
                List.of("r1", "r2"), "v1", null, 168);
        TieringKVClusterStatus status = new TieringKVClusterStatus(3, 3,
                1, 1, "none");
        long start = System.nanoTime();
        for (int i = 0; i < count; i++) {
            planner.plan(spec, status, 1);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH OPERATOR-PLAN %d -> %d ops/s%n",
                count, count * 1_000L / Math.max(1, elapsedMs));
    }

    @ParameterizedTest(name = "rounds {0}")
    @ValueSource(ints = {100, 500})
    void checkpointSaveLoadLatency(int rounds) throws Exception {
        Path ckpt = dir.resolve("ckpt-bench");
        byte[] snapshot = new byte[4096];
        long start = System.nanoTime();
        for (int i = 0; i < rounds; i++) {
            CheckpointManager.save(ckpt,
                    new CheckpointManager.Checkpoint(i, i, snapshot));
            CheckpointManager.load(ckpt);
        }
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH CHECKPOINT %d -> %d ms%n",
                rounds, elapsedMs);
        assertThat(CheckpointManager.load(ckpt).watermark())
                .isEqualTo(rounds - 1);
    }

    @Test
    void pitrRecoveryEndToEndLatency() throws Exception {
        Path archive = dir.resolve("e2e-arch");
        MvccStorageEngine engine = new MvccStorageEngine(MemTable.create());
        MvccPitrRecorder recorder = new MvccPitrRecorder(engine, archive);
        for (int i = 0; i < 300; i++) {
            recorder.putVersion(("k" + i).getBytes(),
                    ("v" + i).getBytes(), i, i * 10, WriteType.PUT);
        }
        Path ckpt = dir.resolve("e2e-ckpt");
        CheckpointManager.save(ckpt, new CheckpointManager.Checkpoint(
                recorder.watermark(), 1_000,
                PersistentMvccIndex.snapshotBytes(engine)));
        long start = System.nanoTime();
        MvccStorageEngine restored = RestoreTimeline.restore(
                MemTable.create(), ckpt, archive, Long.MAX_VALUE);
        long elapsedMs = (System.nanoTime() - start) / 1_000_000;
        System.out.printf("PHASE26-BENCH PITR-E2E %d ms%n", elapsedMs);
        assertThat(restored.latestValue(("k299").getBytes()))
                .isEqualTo(("v299").getBytes());
    }
}
