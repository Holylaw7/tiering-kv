package io.tieringkv.command;

import java.util.List;

/** 已解析的 Redis 命令：name 为小写命令名，args 为参数（不含命令名）。 */
public record RespCommand(String name, List<byte[]> args) {

    public RespCommand {
        args = List.copyOf(args);
    }
}
