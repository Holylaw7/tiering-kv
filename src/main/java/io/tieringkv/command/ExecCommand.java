package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.transaction.ExecJournal;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;

/** EXEC：顺序执行队列命令并返回结果数组（ADR-0287）。 */
public final class ExecCommand implements Command {

    private final CommandRegistry registry;
    private final ExecJournal journal;

    public ExecCommand(CommandRegistry registry) {
        this(registry, new ExecJournal());
    }

    public ExecCommand(CommandRegistry registry,
                       ExecJournal journal) {
        this.registry = registry;
        this.journal = journal;
    }

    @Override
    public String name() {
        return "exec";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        if (context == null || !context.inMulti()) {
            return new RespError("ERR EXEC without MULTI");
        }
        List<RespCommand> queue = context.txnQueue();
        if (!context.versionsMatch(key -> storage
                instanceof AtomicStringOps atomic
                ? atomic.versionOf(key) : 0L)) {
            context.clearTxnQueue();
            context.unwatchAll();
            return RespNull.ARRAY;
        }
        context.clearTxnQueue();
        context.unwatchAll();
        CommandEngine engine = new CommandEngine(registry, storage);
        List<RespValue> results = new ArrayList<>();
        Map<byte[], byte[]> oldValues = snapshot(queue, storage);
        boolean failed = false;
        for (RespCommand command : queue) {
            RespValue result = engine.execute(command);
            results.add(result);
            if (result instanceof RespError) {
                failed = true;
                break;
            }
        }
        if (failed) {
            rollback(oldValues, storage);
            journal.record(queue.size(),
                    ExecJournal.Outcome.ROLLED_BACK);
        } else {
            journal.record(queue.size(),
                    ExecJournal.Outcome.SUCCESS);
        }
        return new RespArray(results);
    }

    private static Map<byte[], byte[]> snapshot(
            List<RespCommand> queue, StorageEngine storage) {
        Map<byte[], byte[]> old = new LinkedHashMap<>();
        for (RespCommand command : queue) {
            if (command.name().equals("mset")
                    || command.name().equals("msetnx")) {
                for (int i = 0; i < command.args().size();
                     i += 2) {
                    byte[] key = command.args().get(i);
                    old.putIfAbsent(key, storage.get(key));
                }
            } else if (!command.args().isEmpty()) {
                byte[] key = command.args().get(0);
                old.putIfAbsent(key, storage.get(key));
            }
        }
        return old;
    }

    private static void rollback(Map<byte[], byte[]> old,
                                 StorageEngine storage) {
        for (Map.Entry<byte[], byte[]> entry : old.entrySet()) {
            if (entry.getValue() == null) {
                storage.delete(entry.getKey());
            } else {
                storage.put(entry.getKey(), entry.getValue());
            }
        }
    }
}
