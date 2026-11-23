package io.tieringkv.command;

import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
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

    private final CommandRegistry registry;
    private final StorageEngine storage;
    private final KeyShardExecutor executor;
    private final java.util.function.BooleanSupplier memoryPressure;

    public CommandEngine(CommandRegistry registry, StorageEngine storage) {
        this(registry, storage, null, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage, KeyShardExecutor executor) {
        this(registry, storage, executor, null);
    }

    /**
     * @param memoryPressure 内存压力探针；返回 true 时拒绝写命令（可为 null）
     */
    public CommandEngine(CommandRegistry registry, StorageEngine storage,
                         KeyShardExecutor executor,
                         java.util.function.BooleanSupplier memoryPressure) {
        this.registry = registry;
        this.storage = storage;
        this.executor = executor;
        this.memoryPressure = memoryPressure;
    }

    public RespValue execute(RespCommand command) {
        ConnectionContext context = ConnectionContext.current();
        if (context != null && context.inMulti()
                && !isTxnControl(command.name())) {
            context.enqueue(command);
            return new RespSimpleString("QUEUED");
        }
        // Redis 语义：命令名大小写不敏感；解析器已归一化，此处兜底
        String name = command.name().toLowerCase(Locale.ROOT);
        Command handler = registry.find(name);
        if (handler == null) {
            return RespError.unknownCommand(name);
        }
        if (memoryPressure != null && WriteCommands.isRejectedUnderPressure(name)
                && memoryPressure.getAsBoolean()) {
            return new RespError(OOM_ERROR);
        }
        return handler.execute(command.args(), storage);
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
                ? command.name().getBytes(StandardCharsets.UTF_8)
                : command.args().get(0);
        boolean submitted = executor.submit(key, () -> {
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
        if (!submitted) {
            callback.accept(new RespError("ERR server busy or shutting down"), null);
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
