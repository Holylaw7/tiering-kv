package io.tieringkv.command;

import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.concurrent.CompletableFuture;
import java.util.Locale;

/**
 * 命令执行引擎：注册表查找 + 命令执行。
 * Phase 1 为连接事件循环内同步执行（单连接有序）；key 分片线程池在 Phase 7 落地。
 */
public final class CommandEngine {

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
        // Redis 语义：命令名大小写不敏感；解析器已归一化，此处兜底
        String name = command.name().toLowerCase(Locale.ROOT);
        Command handler = registry.find(name);
        if (handler == null) {
            return RespError.unknownCommand(name);
        }
        return handler.execute(command.args(), storage);
    }

    /**
     * 异步执行（ADR-0023）：配置了 KeyShardExecutor 时按 key 分片并行，
     * 同键 FIFO；未配置时同步返回。异常通过 future 传播。
     */
    public CompletableFuture<RespValue> executeAsync(RespCommand command) {
        if (executor == null) {
            return CompletableFuture.completedFuture(execute(command));
        }
        byte[] key = command.args().isEmpty()
                ? command.name().getBytes(StandardCharsets.UTF_8)
                : command.args().get(0);
        CompletableFuture<RespValue> future = new CompletableFuture<>();
        executor.submit(key, () -> {
            try {
                future.complete(execute(command));
            } catch (Throwable t) {
                future.completeExceptionally(t);
            }
        });
        return future;
    }
}
