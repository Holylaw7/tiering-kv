package io.tieringkv.command;

import io.tieringkv.protocol.RespValue;

import java.util.List;

/**
 * Redis 命令。Phase 1 执行模型为连接事件循环内同步执行：
 * 每个命令原子完成并返回 RESP 值（ADR-0006）。
 */
public interface Command {

    /** 小写命令名，作为注册表键。 */
    String name();

    RespValue execute(List<byte[]> args, KVStore store);
}
