package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** FLUSHDB / FLUSHALL：清空（单库模型下等价，ADR-0272）。 */
public final class FlushCommand implements Command {

    private final String name;

    public FlushCommand(String name) {
        this.name = name;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name);
        }
        storage.clear();
        return new RespSimpleString("OK");
    }
}
