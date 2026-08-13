package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** MULTI：开启连接级事务队列（ADR-0287）。 */
public final class MultiCommand implements Command {

    @Override
    public String name() {
        return "multi";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        if (context == null) {
            return new RespSimpleString("OK");
        }
        if (context.inMulti()) {
            return new RespError(
                    "ERR MULTI calls can not be nested");
        }
        context.setInMulti(true);
        return new RespSimpleString("OK");
    }
}
