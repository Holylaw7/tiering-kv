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

    private final CommandRegistry registry;
    private final StorageEngine storage;
    private final KeyShardExecutor executor;

    public CommandEngine(CommandRegistry registry, StorageEngine storage) {
        this(registry, storage, null);
    }

    public CommandEngine(CommandRegistry registry, StorageEngine storage, KeyShardExecutor executor) {
        this.registry = registry;
        this.storage = storage;
        this.executor = executor;
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
        byte[] key = command.args().isEmpty()
                ? command.name().getBytes(StandardCharsets.UTF_8)
                : command.args().get(0);
        executor.submit(key, () -> {
            try {
                boolean attached = captured != null;
                if (attached) {
                    ConnectionContext.attach(captured);
                }
                try {
                    callback.accept(execute(command), null);
                } finally {
                    if (attached) {
                        ConnectionContext.detach();
                    }
                }
            } catch (Throwable t) {
                callback.accept(null, t);
            }
        });
    }
}
