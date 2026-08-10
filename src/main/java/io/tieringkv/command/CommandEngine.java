package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.Locale;

/**
 * 命令执行引擎：注册表查找 + 命令执行。
 * Phase 1 为连接事件循环内同步执行（单连接有序）；key 分片线程池在 Phase 7 落地。
 */
public final class CommandEngine {

    private final CommandRegistry registry;
    private final StorageEngine storage;

    public CommandEngine(CommandRegistry registry, StorageEngine storage) {
        this.registry = registry;
        this.storage = storage;
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
}
