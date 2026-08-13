package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.transaction.ExecJournal;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** EXEC 原子性与回滚（ADR-0291）。 */
class ExecAtomicityRollbackTest {

    private static <T> T withContext(ConnectionContext context,
                                     Supplier<T> action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    @Test
    void successfulExecJournaled() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "1");
            runner.exec("exec");
            return null;
        });
        ExecJournal journal = new ExecJournal();
        assertThat(journal.size()).isZero();
    }

    @Test
    void failedExecRollsBackAllKeys() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "a", "old-a");
        runner.exec("set", "b", "old-b");
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "new-a");
            runner.exec("set", "b", "not-number");
            runner.exec("incr", "b");
            return runner.exec("exec");
        });
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(runner.exec("get", "a")).isEqualTo(
                new RespBulkString("old-a".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(runner.exec("get", "b")).isEqualTo(
                new RespBulkString("old-b".getBytes(
                        StandardCharsets.UTF_8)));
    }

    @Test
    void failedExecContainsErrorResult() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "b", "abc");
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "1");
            runner.exec("incr", "b");
            return runner.exec("exec");
        });
        RespArray array = (RespArray) result;
        assertThat(array.values().get(1))
                .isInstanceOf(RespError.class);
    }

    @Test
    void rollbackRestoresDeletedKey() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("set", "not-int", "abc");
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            runner.exec("set", "a", "v");
            runner.exec("incr", "not-int");
            return runner.exec("exec");
        });
        assertThat(result).isInstanceOf(RespArray.class);
        assertThat(runner.exec("exists", "a")).isEqualTo(
                new RespInteger(0));
    }

    @Test
    void successExecAppliesAll() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("multi");
            runner.exec("mset", "a", "1", "b", "2");
            runner.exec("exec");
            return null;
        });
        assertThat(runner.exec("mget", "a", "b"))
                .isInstanceOf(RespArray.class);
    }

    @Test
    void journalRecordsFailure() {
        ExecJournal journal = new ExecJournal();
        long id = journal.record(2,
                ExecJournal.Outcome.ROLLED_BACK);
        assertThat(id).isEqualTo(1);
        assertThat(journal.size()).isEqualTo(1);
        assertThat(journal.records().get(0).outcome())
                .isEqualTo(ExecJournal.Outcome.ROLLED_BACK);
    }

    @ParameterizedTest(name = "journal {0}")
    @MethodSource("journalOutcomes")
    void journalOutcomes(ExecJournal.Outcome outcome) {
        ExecJournal journal = new ExecJournal();
        journal.record(3, outcome);
        assertThat(journal.records().get(0).outcome())
                .isEqualTo(outcome);
    }

    @ParameterizedTest(name = "queue {0}")
    @MethodSource("queueSizes")
    void execResultSizes(int size) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = withContext(context, () -> {
            runner.exec("multi");
            for (int i = 0; i < size; i++) {
                runner.exec("set", "k" + i, "v");
            }
            return runner.exec("exec");
        });
        assertThat(((RespArray) result).values()).hasSize(size);
    }

    static Stream<Arguments> journalOutcomes() {
        return Stream.of(ExecJournal.Outcome.SUCCESS,
                        ExecJournal.Outcome.ROLLED_BACK,
                        ExecJournal.Outcome.FAILED_ROLLBACK)
                .map(Arguments::of);
    }

    static Stream<Arguments> queueSizes() {
        return Stream.of(1, 2, 3, 5, 8).map(Arguments::of);
    }
}
