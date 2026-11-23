package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** UNWATCH：清空观察集（ADR-0290）。 */
public final class UnwatchCommand implements Command {

    @Override
    public String name() {
        return "unwatch";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        if (context != null) {
            context.unwatchAll();
        }
        return new RespSimpleString("OK");
    }
}
