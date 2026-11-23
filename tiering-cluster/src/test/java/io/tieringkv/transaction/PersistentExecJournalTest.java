package io.tieringkv.transaction;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 持久化 ExecJournal（ADR-0301）。 */
class PersistentExecJournalTest {

    @Test
    void appendAndReload() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(2, ExecJournal.Outcome.SUCCESS);
        journal.append(3, ExecJournal.Outcome.ROLLED_BACK);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        assertThat(reloaded.size()).isEqualTo(2);
        assertThat(reloaded.records().get(1).outcome())
                .isEqualTo(ExecJournal.Outcome.ROLLED_BACK);
    }

    @Test
    void truncatedTailIgnored() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(1, ExecJournal.Outcome.SUCCESS);
        journal.append(2, ExecJournal.Outcome.SUCCESS);
        byte[] bytes = Files.readAllBytes(file);
        Files.write(file, java.util.Arrays.copyOf(bytes,
                bytes.length - 5));
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        assertThat(reloaded.size()).isEqualTo(1);
    }

    @Test
    void corruptTailIgnored() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(1, ExecJournal.Outcome.SUCCESS);
        journal.append(2, ExecJournal.Outcome.SUCCESS);
        byte[] bytes = Files.readAllBytes(file);
        bytes[bytes.length - 1] ^= 0x7f;
        Files.write(file, bytes);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        assertThat(reloaded.size()).isEqualTo(1);
    }

    @Test
    void missingFileEmptyJournal() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        PersistentExecJournal journal = new PersistentExecJournal(
                dir.resolve("nope.log"));
        assertThat(journal.size()).isZero();
    }

    @Test
    void txnIdContinuesAfterReload() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(1, ExecJournal.Outcome.SUCCESS);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        long next = reloaded.append(1,
                ExecJournal.Outcome.SUCCESS);
        assertThat(next).isEqualTo(2);
    }

    @Test
    void recordsPreserveAllFields() throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(7, ExecJournal.Outcome.FAILED_ROLLBACK);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        PersistentExecJournal.FileRecord record =
                reloaded.records().get(0);
        assertThat(record.commandCount()).isEqualTo(7);
        assertThat(record.outcome()).isEqualTo(
                ExecJournal.Outcome.FAILED_ROLLBACK);
        assertThat(record.txnId()).isEqualTo(1);
    }

    @ParameterizedTest(name = "outcome {0}")
    @MethodSource("outcomes")
    void outcomeRoundTrip(ExecJournal.Outcome outcome)
            throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(2, outcome);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        assertThat(reloaded.records().get(0).outcome())
                .isEqualTo(outcome);
    }

    @ParameterizedTest(name = "count {0}")
    @MethodSource("counts")
    void countRoundTrip(int count) throws Exception {
        Path dir = Files.createTempDirectory("exec-journal");
        Path file = dir.resolve("exec.log");
        PersistentExecJournal journal =
                new PersistentExecJournal(file);
        journal.append(count, ExecJournal.Outcome.SUCCESS);
        PersistentExecJournal reloaded =
                new PersistentExecJournal(file);
        assertThat(reloaded.records().get(0).commandCount())
                .isEqualTo(count);
    }

    static Stream<Arguments> outcomes() {
        return Stream.of(ExecJournal.Outcome.SUCCESS,
                        ExecJournal.Outcome.ROLLED_BACK,
                        ExecJournal.Outcome.FAILED_ROLLBACK)
                .map(Arguments::of);
    }

    static Stream<Arguments> counts() {
        return Stream.of(0, 1, 5, 50, 200).map(Arguments::of);
    }
}
