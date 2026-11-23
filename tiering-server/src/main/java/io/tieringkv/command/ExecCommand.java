package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/** EXEC：顺序执行队列命令并返回结果数组（ADR-0287）。 */
public final class ExecCommand implements Command {

    private static final AtomicLong DEFAULT_SEQ = new AtomicLong();
    private static final ExecRecorder DEFAULT_RECORDER =
            (count, outcome) -> DEFAULT_SEQ.incrementAndGet();

    private final CommandRegistry registry;
    private final ExecRecorder journal;
    private final AclPolicy aclPolicy;

    public ExecCommand(CommandRegistry registry) {
        this(registry, DEFAULT_RECORDER, null);
    }

    public ExecCommand(CommandRegistry registry, AclPolicy aclPolicy) {
        this(registry, DEFAULT_RECORDER, aclPolicy);
    }

    public ExecCommand(CommandRegistry registry, ExecRecorder journal) {
        this(registry, journal, null);
    }

    public ExecCommand(CommandRegistry registry, ExecRecorder journal, AclPolicy aclPolicy) {
        this.registry = registry;
        this.journal = journal == null ? DEFAULT_RECORDER : journal;
        this.aclPolicy = aclPolicy;
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
        CommandEngine engine = new CommandEngine(registry, storage, aclPolicy);
        List<RespValue> results = new ArrayList<>();
        Map<ByteArrayKey, SnapshotEntry> oldValues = snapshot(queue, storage);
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
            journal.recordOutcome(queue.size(),
                    ExecRecorder.Outcome.ROLLED_BACK);
        } else {
            journal.recordOutcome(queue.size(),
                    ExecRecorder.Outcome.SUCCESS);
        }
        return new RespArray(results);
    }

    private record SnapshotEntry(byte[] value, long ttlMillis) { }

    private static Map<ByteArrayKey, SnapshotEntry> snapshot(
            List<RespCommand> queue, StorageEngine storage) {
        Map<ByteArrayKey, SnapshotEntry> old = new LinkedHashMap<>();
        for (RespCommand command : queue) {
            if (command.name().equals("mset")
                    || command.name().equals("msetnx")) {
                for (int i = 0; i < command.args().size();
                     i += 2) {
                    putSnapshotIfAbsent(old, command.args().get(i), storage);
                }
            } else if (!command.args().isEmpty()) {
                putSnapshotIfAbsent(old, command.args().get(0), storage);
            }
        }
        return old;
    }

    private static void putSnapshotIfAbsent(Map<ByteArrayKey, SnapshotEntry> old,
                                            byte[] rawKey,
                                            StorageEngine storage) {
        ByteArrayKey key = new ByteArrayKey(rawKey);
        if (!old.containsKey(key)) {
            byte[] value = storage.get(key.data());
            long ttl = (value != null && storage instanceof AtomicStringOps atomic)
                    ? atomic.ttlMillis(key.data())
                    : -1L;
            old.put(key, new SnapshotEntry(value, ttl));
        }
    }

    private static void rollback(Map<ByteArrayKey, SnapshotEntry> old,
                                 StorageEngine storage) {
        for (Map.Entry<ByteArrayKey, SnapshotEntry> entry : old.entrySet()) {
            byte[] key = entry.getKey().data();
            SnapshotEntry snap = entry.getValue();
            if (snap.value() == null) {
                storage.delete(key);
            } else if (snap.ttlMillis() > 0) {
                storage.put(key, snap.value(), snap.ttlMillis());
            } else {
                storage.put(key, snap.value());
            }
        }
    }
}
