package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.types.ByteArrayKey;

import java.util.List;

/** WATCH key...：记录被观察键版本（ADR-0290）。 */
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
        ConnectionContext context = ConnectionContext.current();
        if (context != null) {
            for (byte[] key : args) {
                long version = storage instanceof AtomicStringOps
                        atomic ? atomic.versionOf(key) : 0;
                context.watch(new ByteArrayKey(key), version);
            }
        }
        return new RespSimpleString("OK");
    }
}
