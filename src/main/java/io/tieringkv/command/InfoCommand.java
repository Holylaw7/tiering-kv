package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.function.Supplier;

/** INFO：返回服务端指标文本（MetricsRegistry，ADR-0034）。 */
public final class InfoCommand implements Command {

    private final Supplier<String> infoProvider;

    public InfoCommand(Supplier<String> infoProvider) {
        this.infoProvider = infoProvider;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (!args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        return new RespBulkString(infoProvider.get().getBytes(StandardCharsets.UTF_8));
    }
}
