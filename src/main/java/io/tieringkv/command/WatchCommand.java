package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/**
 * WATCH key...：返回 OK（ADR-0287 限制登记：无版本守卫，
 * 乐观并发校验由 MVCC 事务路径提供）。
 */
public final class WatchCommand implements Command {

    @Override
    public String name() {
        return "watch";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        return new RespSimpleString("OK");
    }
}
