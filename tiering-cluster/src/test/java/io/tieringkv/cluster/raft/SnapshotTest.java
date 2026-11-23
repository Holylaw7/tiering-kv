package io.tieringkv.cluster.raft;

import io.tieringkv.cluster.raft.snapshot.SnapshotManager;
import io.tieringkv.cluster.raft.snapshot.SnapshotMetadata;
import io.tieringkv.cluster.raft.snapshot.SnapshotReader;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Raft Snapshot（ADR-0040）：创建/加载/校验/恢复。 */
class SnapshotTest {

    @TempDir
    Path dir;

    @Test
    void createSnapshotWritesFile() throws IOException {
        AtomicReference<byte[]> state = new AtomicReference<>(bytes("state-1"));
        SnapshotManager manager = SnapshotManager.open(dir, state::get, state::set);
        assertThat(manager.create(100, 3)).isTrue();
        assertThat(Files.exists(dir.resolve("snapshot.latest"))).isTrue();
    }

    @Test
    void metadataRoundTrip() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("s"), ignored -> {
        });
        manager.create(90000, 42);
        assertThat(manager.hasSnapshot()).isTrue();
        assertThat(manager.metadata()).isEqualTo(new SnapshotMetadata(90000, 42));
    }

    @Test
    void stateRoundTrip() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("hello-snapshot"), ignored -> {
        });
        manager.create(5, 1);
        assertThat(manager.data()).isEqualTo(bytes("hello-snapshot"));
    }

    @Test
    void loadExistingSnapshotOnRestart() throws IOException {
        AtomicReference<byte[]> state = new AtomicReference<>(bytes("before"));
        SnapshotManager manager = SnapshotManager.open(dir, state::get, state::set);
        manager.create(77, 9);

        AtomicReference<byte[]> restored = new AtomicReference<>(bytes("after"));
        SnapshotManager reopened = SnapshotManager.open(dir, restored::get, restored::set);
        assertThat(reopened.hasSnapshot()).isTrue();
        assertThat(reopened.metadata()).isEqualTo(new SnapshotMetadata(77, 9));
        assertThat(restored.get()).isEqualTo(bytes("before"));
    }

    @Test
    void corruptedCrcRejectedByReader() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("data"), ignored -> {
        });
        manager.create(10, 2);
        byte[] file = Files.readAllBytes(dir.resolve("snapshot.latest"));
        file[file.length - 1] ^= 0x01;
        Files.write(dir.resolve("snapshot.latest"), file);
        assertThatThrownBy(() -> SnapshotReader.read(dir))
                .isInstanceOf(IOException.class)
                .hasMessageContaining("crc");
    }

    @Test
    void corruptedSnapshotOpenThrows() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("d"), ignored -> {
        });
        manager.create(10, 2);
        byte[] file = Files.readAllBytes(dir.resolve("snapshot.latest"));
        file[10] ^= 0x7F;
        Files.write(dir.resolve("snapshot.latest"), file);
        assertThatThrownBy(() -> SnapshotManager.open(dir, () -> bytes("x"), ignored -> {
        })).isInstanceOf(IOException.class);
    }

    @Test
    void installSnapshotRestoresStateMachine() throws IOException {
        AtomicReference<byte[]> state = new AtomicReference<>(bytes("old"));
        SnapshotManager manager = SnapshotManager.open(dir, state::get, state::set);
        assertThat(manager.install(200, 7, bytes("new-state"))).isTrue();
        assertThat(state.get()).isEqualTo(bytes("new-state"));
        assertThat(manager.metadata()).isEqualTo(new SnapshotMetadata(200, 7));
    }

    @Test
    void hasSnapshotFalseBeforeCreate() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("x"), ignored -> {
        });
        assertThat(manager.hasSnapshot()).isFalse();
    }

    @Test
    void createOverwritesPreviousSnapshot() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> bytes("v1"), ignored -> {
        });
        manager.create(10, 1);
        SnapshotManager manager2 = SnapshotManager.open(dir, () -> bytes("v2"), ignored -> {
        });
        manager2.create(20, 2);
        assertThat(manager2.metadata()).isEqualTo(new SnapshotMetadata(20, 2));
        assertThat(manager2.data()).isEqualTo(bytes("v2"));
    }

    @Test
    void emptyStateSnapshotRoundTrip() throws IOException {
        SnapshotManager manager = SnapshotManager.open(dir, () -> new byte[0], ignored -> {
        });
        manager.create(1, 1);
        assertThat(manager.data()).isEmpty();
        SnapshotManager reopened = SnapshotManager.open(dir, () -> new byte[0], ignored -> {
        });
        assertThat(reopened.data()).isEmpty();
    }

    @Test
    void missingSnapshotReaderReturnsNull() throws IOException {
        assertThat(SnapshotReader.read(dir)).isNull();
    }

    @Test
    void snapshotDataReturnsCopy() throws IOException {
        byte[] state = bytes("immutable");
        SnapshotManager manager = SnapshotManager.open(dir, () -> state, ignored -> {
        });
        manager.create(3, 1);
        byte[] copy = manager.data();
        copy[0] = 'X';
        assertThat(manager.data()).isEqualTo(state);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
