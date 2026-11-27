package io.tieringkv.command;

import io.tieringkv.execution.BatchableShardTask;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.observability.SlowLog;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.WriteOp;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.Locale;
import java.util.function.BiConsumer;

/**
 * 命令执行引擎：注册表查找 + 命令执行。
 * Phase 1 为连接事件循环内同步执行（单连接有序）；key 分片线程池在 Phase 7 落地。
 */
public final class CommandEngine {

    /** 无参命令 key 字节缓存（ADR-0330，TD-020）：PING/ECHO 等每请求
     *  不再分配新 byte[]。 */
    private static final Map<String, byte[]> COMMAND_KEYS =
            new ConcurrentHashMap<>();

    /** OOM 语义与 Redis 对齐：内存超限时拒绝写命令，读命令继续服务。 */
    public static final String OOM_ERROR =
            "OOM command not allowed when used memory > 'maxmemory'.";
    private static final RespSimpleString QUEUED = new RespSimpleString("QUEUED");
    private static final RespSimpleString OK = new RespSimpleString("OK");
    private static final RespInteger INT_ONE = new RespInteger(1);
    private static final RespInteger INT_ZERO = new RespInteger(0);

    private final CommandRegistry registry;
    private final StorageEngine storage;
    private final KeyShardExecutor executor;
    private final java.util.function.BooleanSupplier memoryPressure;
    private final AclPolicy aclPolicy;
    private final SlowLog slowLog;
    private final boolean standardSet;
    private final boolean standardDel;

    public CommandEngine(CommandRegistry registry, StorageEngine storage) {
        this(registry, storage, null, null, null, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage, AclPolicy aclPolicy) {
        this(registry, storage, null, null, aclPolicy, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage, KeyShardExecutor executor) {
        this(registry, storage, executor, null, null, null);
    }

    /**
     * @param memoryPressure 内存压力探针；返回 true 时拒绝写命令（可为 null）
     */
    public CommandEngine(CommandRegistry registry, StorageEngine storage,
                         KeyShardExecutor executor,
                         java.util.function.BooleanSupplier memoryPressure) {
        this(registry, storage, executor, memoryPressure, null, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage,
                         KeyShardExecutor executor,
                         java.util.function.BooleanSupplier memoryPressure,
                         AclPolicy aclPolicy) {
        this(registry, storage, executor, memoryPressure, aclPolicy, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage,
                         KeyShardExecutor executor,
                         java.util.function.BooleanSupplier memoryPressure,
                         AclPolicy aclPolicy,
                         SlowLog slowLog) {
        this.registry = registry;
        this.storage = storage;
        this.executor = executor;
        this.memoryPressure = memoryPressure;
        this.aclPolicy = aclPolicy;
        this.slowLog = slowLog;
        this.standardSet = registry.find("set") instanceof SetCommand;
        this.standardDel = registry.find("del") instanceof DelCommand;
    }

    public RespValue execute(RespCommand command) {
        RespError accessError = checkAccess(command);
        if (accessError != null) {
            return accessError;
        }
        ConnectionContext context = ConnectionContext.current();
        if (context != null && context.inMulti()
                && !isTxnControl(command.name())) {
            context.enqueue(command);
            return QUEUED;
        }
        // Redis 语义：命令名大小写不敏感；RespRequestParser 已归一化，此处快路径免分配
        String rawName = command.name();
        String name = isLowerAscii(rawName) ? rawName : rawName.toLowerCase(Locale.ROOT);
        Command handler = registry.find(name);
        if (handler == null) {
            return RespError.unknownCommand(name);
        }
        if (memoryPressure != null && WriteCommands.isRejectedUnderPressure(name)
                && memoryPressure.getAsBoolean()) {
            return new RespError(OOM_ERROR);
        }
        if (slowLog == null) {
            return handler.execute(command.args(), storage);
        }
        long startNanos = System.nanoTime();
        try {
            return handler.execute(command.args(), storage);
        } finally {
            slowLog.record(name, command.args(), System.nanoTime() - startNanos);
        }
    }

    private RespError checkAccess(RespCommand command) {
        if (aclPolicy == null) {
            return null;
        }
        ConnectionContext context = ConnectionContext.current();
        String user = context == null ? null : context.authenticatedUser();
        String denied = aclPolicy.authorize(user, command.name(), command.args());
        return denied == null ? null : new RespError(denied);
    }

    private static boolean isLowerAscii(String text) {
        int len = text.length();
        for (int i = 0; i < len; i++) {
            char c = text.charAt(i);
            if (c >= 'A' && c <= 'Z' || c > 0x7f) {
                return false;
            }
        }
        return true;
    }

    private static boolean isTxnControl(String name) {
        return switch (name) {
            case "multi", "exec", "discard", "watch" -> true;
            default -> false;
        };
    }

    /**
     * 异步执行（ADR-0023）：配置了 KeyShardExecutor 时按 key 分片并行，
     * 同键 FIFO；未配置时同步返回。异常通过 future 传播。
     */
    public CompletableFuture<RespValue> executeAsync(RespCommand command) {
        if (executor == null) {
            return CompletableFuture.completedFuture(execute(command));
        }
        ConnectionContext captured = ConnectionContext.current();
        byte[] key = command.args().isEmpty()
                ? COMMAND_KEYS.computeIfAbsent(
                        command.name(),
                        name -> name.getBytes(StandardCharsets.UTF_8))
                : command.args().get(0);
        CompletableFuture<RespValue> future = new CompletableFuture<>();
        executor.submit(key, () -> {
            try {
                boolean attached = captured != null;
                if (attached) {
                    ConnectionContext.attach(captured);
                }
                try {
                    future.complete(execute(command));
                } finally {
                    if (attached) {
                        ConnectionContext.detach();
                    }
                }
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }

    /**
     * 回调式异步执行（ADR-0033）：避免每请求 CompletableFuture，
     * 分片 worker 完成后直接回调（异常经 callback 传递）。
     */
    public void executeAsync(RespCommand command, BiConsumer<RespValue, Throwable> callback) {
        if (executor == null) {
            try {
                callback.accept(execute(command), null);
            } catch (Throwable t) {
                callback.accept(null, t);
            }
            return;
        }
        ConnectionContext captured = ConnectionContext.current();
        if ((captured == null || !captured.inMulti())
                && ("blpop".equals(command.name()) || "brpop".equals(command.name()))) {
            executeBlockingPopAsync(command, captured, callback);
            return;
        }
        byte[] key = command.args().isEmpty()
                ? COMMAND_KEYS.computeIfAbsent(
                        command.name(),
                        name -> name.getBytes(StandardCharsets.UTF_8))
                : command.args().get(0);
        WriteOp batchOp = (captured == null || !captured.inMulti())
                ? tryParseBatchableWrite(command)
                : null;
        boolean submitted;
        if (batchOp != null) {
            submitted = executor.submit(key, new BatchWriteCommandTask(batchOp, command, captured, callback));
        } else {
            submitted = executor.submit(key, () -> {
                ConnectionContext previous = ConnectionContext.current();
                try {
                    if (captured != null) {
                        ConnectionContext.attach(captured);
                    } else {
                        ConnectionContext.detach();
                    }
                    callback.accept(execute(command), null);
                } catch (Throwable t) {
                    callback.accept(null, t);
                } finally {
                    ConnectionContext.restore(previous);
                }
            });
        }
        if (!submitted) {
            callback.accept(new RespError("ERR server busy or shutting down"), null);
        }
    }

    /**
     * 尝试把标准单键纯写命令（SET k v [EX|PX t] / 单键 DEL k）解析为零拷贝 {@link WriteOp}；
     * 语法错误、非标准命令覆写或其他命令均返回 null，走常规 {@link #execute} 路径保持错误语义 100% 一致。
     */
    private WriteOp tryParseBatchableWrite(RespCommand command) {
        String raw = command.name();
        String name = isLowerAscii(raw) ? raw : raw.toLowerCase(Locale.ROOT);
        List<byte[]> args = command.args();
        if (standardSet && "set".equals(name)) {
            if (args.size() == 2) {
                return WriteOp.put(args.get(0), args.get(1));
            }
            if (args.size() == 4) {
                String option = new String(args.get(2), StandardCharsets.UTF_8).toLowerCase(Locale.ROOT);
                try {
                    long parsed = Long.parseLong(new String(args.get(3), StandardCharsets.UTF_8).trim());
                    if ("ex".equals(option)) {
                        return WriteOp.put(args.get(0), args.get(1), Math.multiplyExact(parsed, 1000L));
                    }
                    if ("px".equals(option)) {
                        return WriteOp.put(args.get(0), args.get(1), parsed);
                    }
                } catch (NumberFormatException | ArithmeticException ignored) {
                    return null;
                }
            }
            return null;
        }
        if (standardDel && "del".equals(name) && args.size() == 1) {
            return WriteOp.delete(args.get(0));
        }
        return null;
    }

    private static final ThreadLocal<BatchScratch> TL_BATCH_SCRATCH =
            ThreadLocal.withInitial(BatchScratch::new);

    private static final class BatchScratch {
        final List<BatchWriteCommandTask> validTasks = new ArrayList<>(64);
        final List<WriteOp> validOps = new ArrayList<>(64);
    }

    private void executeWriteBatch(int shardId, List<BatchableShardTask> rawTasks) {
        int total = rawTasks.size();
        BatchScratch scratch = TL_BATCH_SCRATCH.get();
        List<BatchWriteCommandTask> validTasks = scratch.validTasks;
        List<WriteOp> validOps = scratch.validOps;
        validTasks.clear();
        validOps.clear();
        boolean oom = memoryPressure != null && memoryPressure.getAsBoolean();

        try {
            for (int i = 0; i < total; i++) {
                BatchWriteCommandTask task = (BatchWriteCommandTask) rawTasks.get(i);
                if (aclPolicy != null) {
                    String user = task.captured == null ? null : task.captured.authenticatedUser();
                    String denied = aclPolicy.authorize(user, task.command.name(), task.command.args());
                    if (denied != null) {
                        task.callback.accept(new RespError(denied), null);
                        continue;
                    }
                }
                if (oom && !task.op.delete()) {
                    task.callback.accept(new RespError(OOM_ERROR), null);
                    continue;
                }
                validTasks.add(task);
                validOps.add(task.op);
            }
            if (validTasks.isEmpty()) {
                return;
            }
            long startNanos = slowLog != null ? System.nanoTime() : 0L;
            long deletedMask;
            try {
                deletedMask = storage.applyWriteBatch(shardId, validOps);
            } catch (Throwable t) {
                for (int i = 0; i < validTasks.size(); i++) {
                    validTasks.get(i).callback.accept(null, t);
                }
                return;
            }
            if (slowLog != null) {
                long elapsed = System.nanoTime() - startNanos;
                long perOp = elapsed / validTasks.size();
                for (int i = 0; i < validTasks.size(); i++) {
                    BatchWriteCommandTask task = validTasks.get(i);
                    slowLog.record(task.command.name(), task.command.args(), perOp);
                }
            }
            for (int i = 0; i < validTasks.size(); i++) {
                BatchWriteCommandTask task = validTasks.get(i);
                RespValue response;
                if (task.op.delete()) {
                    response = (i < 64 && (deletedMask & (1L << i)) != 0L) ? INT_ONE : INT_ZERO;
                } else {
                    response = OK;
                }
                task.callback.accept(response, null);
            }
        } finally {
            validTasks.clear();
            validOps.clear();
        }
    }

    private final class BatchWriteCommandTask implements BatchableShardTask {
        final WriteOp op;
        final RespCommand command;
        final ConnectionContext captured;
        final BiConsumer<RespValue, Throwable> callback;

        BatchWriteCommandTask(WriteOp op,
                              RespCommand command,
                              ConnectionContext captured,
                              BiConsumer<RespValue, Throwable> callback) {
            this.op = op;
            this.command = command;
            this.captured = captured;
            this.callback = callback;
        }

        @Override
        public Object batchKey() {
            return CommandEngine.this;
        }

        @Override
        public void run() {
            ConnectionContext previous = ConnectionContext.current();
            try {
                if (captured != null) {
                    ConnectionContext.attach(captured);
                } else {
                    ConnectionContext.detach();
                }
                callback.accept(execute(command), null);
            } catch (Throwable t) {
                callback.accept(null, t);
            } finally {
                ConnectionContext.restore(previous);
            }
        }

        @Override
        public void runBatch(int shardId, List<BatchableShardTask> tasks) {
            executeWriteBatch(shardId, tasks);
        }
    }

    private void executeBlockingPopAsync(RespCommand command,
                                         ConnectionContext captured,
                                         BiConsumer<RespValue, Throwable> callback) {
        Command resolved = registry.find(command.name());
        if (!(resolved instanceof ListCommand listCommand)) {
            callback.accept(new RespError("ERR unknown command '" + command.name() + "'"), null);
            return;
        }
        ListCommand.BlockingPopSpec spec = ListCommand.parseBlockingSpec(
                command.name(), command.args());
        if (spec.error() != null) {
            callback.accept(spec.error(), null);
            return;
        }
        byte[] routingKey = spec.keys().get(0);
        long deadline = spec.timeoutMillis() == 0
                ? Long.MAX_VALUE
                : System.currentTimeMillis() + spec.timeoutMillis();
        boolean submitted = executor.submit(routingKey, () -> {
            ConnectionContext previous = ConnectionContext.current();
            try {
                if (captured != null) {
                    ConnectionContext.attach(captured);
                } else {
                    ConnectionContext.detach();
                }
                RespError accessError = checkAccess(command);
                if (accessError != null) {
                    callback.accept(accessError, null);
                    return;
                }
                if (memoryPressure != null
                        && WriteCommands.isRejectedUnderPressure(command.name())
                        && memoryPressure.getAsBoolean()) {
                    callback.accept(new RespError(OOM_ERROR), null);
                    return;
                }
                RespValue immediate = listCommand.tryBlockingPopNow(spec, storage);
                if (immediate != null) {
                    callback.accept(immediate, null);
                    return;
                }
                if (spec.timeoutMillis() > 0 && System.currentTimeMillis() >= deadline) {
                    callback.accept(io.tieringkv.protocol.RespNull.ARRAY, null);
                    return;
                }
                Thread.startVirtualThread(() -> awaitAndPopAsync(
                        listCommand, spec, deadline, captured, routingKey, callback));
            } catch (Throwable t) {
                callback.accept(null, t);
            } finally {
                ConnectionContext.restore(previous);
            }
        });
        if (!submitted) {
            callback.accept(new RespError("ERR server busy or shutting down"), null);
        }
    }

    private void awaitAndPopAsync(ListCommand listCommand,
                                  ListCommand.BlockingPopSpec spec,
                                  long deadline,
                                  ConnectionContext captured,
                                  byte[] routingKey,
                                  BiConsumer<RespValue, Throwable> callback) {
        try {
            while (!Thread.currentThread().isInterrupted()) {
                long remaining = deadline == Long.MAX_VALUE
                        ? 50L
                        : deadline - System.currentTimeMillis();
                if (remaining > 0) {
                    io.tieringkv.operations.BlockingListNotifier.awaitAnyPush(
                            spec.keys(), Math.min(remaining, 50L));
                }
                boolean timedOut = deadline != Long.MAX_VALUE
                        && System.currentTimeMillis() >= deadline;
                java.util.concurrent.CompletableFuture<RespValue> attempt =
                        new java.util.concurrent.CompletableFuture<>();
                boolean submitted = executor.submit(routingKey, () -> {
                    ConnectionContext previous = ConnectionContext.current();
                    try {
                        if (captured != null) {
                            ConnectionContext.attach(captured);
                        } else {
                            ConnectionContext.detach();
                        }
                        attempt.complete(listCommand.tryBlockingPopNow(spec, storage));
                    } catch (Throwable t) {
                        attempt.completeExceptionally(t);
                    } finally {
                        ConnectionContext.restore(previous);
                    }
                });
                if (!submitted) {
                    callback.accept(new RespError("ERR server shutting down"), null);
                    return;
                }
                RespValue popped = attempt.get();
                if (popped != null) {
                    callback.accept(popped, null);
                    return;
                }
                if (timedOut) {
                    callback.accept(io.tieringkv.protocol.RespNull.ARRAY, null);
                    return;
                }
            }
            callback.accept(new RespError("ERR interrupted"), null);
        } catch (Throwable t) {
            callback.accept(null, t);
        }
    }
}
