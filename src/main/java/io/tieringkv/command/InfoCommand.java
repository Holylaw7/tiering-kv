package io.tieringkv.command;

import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

/** INFO [section]：返回服务端指标文本（ADR-0034/0056）。 */
public final class InfoCommand implements Command {

    private final Supplier<String> infoProvider;
    private final Map<String, Supplier<String>> sections;

    public InfoCommand(Supplier<String> infoProvider) {
        this(infoProvider, Map.of());
    }

    public InfoCommand(Supplier<String> infoProvider,
                       Map<String, Supplier<String>> sections) {
        this.infoProvider = infoProvider;
        this.sections = sections;
    }

    @Override
    public String name() {
        return "info";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.size() > 1) {
            return RespError.wrongArity(name());
        }
        if (args.isEmpty()) {
            return new RespBulkString(
                    infoProvider.get().getBytes(StandardCharsets.UTF_8));
        }
        String section = new String(args.get(0), StandardCharsets.UTF_8)
                .toLowerCase();
        Supplier<String> provider = sections.get(section);
        if (provider == null) {
            return new RespError("ERR unknown info section '" + section + "'");
        }
        return new RespBulkString(
                provider.get().getBytes(StandardCharsets.UTF_8));
    }
}
