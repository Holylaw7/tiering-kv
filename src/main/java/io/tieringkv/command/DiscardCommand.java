package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** DISCARD：清空连接级事务队列（ADR-0287）。 */
public final class DiscardCommand implements Command {

    @Override
    public String name() {
        return "discard";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        if (context == null || !context.inMulti()) {
            return new RespError("ERR DISCARD without MULTI");
        }
        context.clearTxnQueue();
        return new RespSimpleString("OK");
    }
}
