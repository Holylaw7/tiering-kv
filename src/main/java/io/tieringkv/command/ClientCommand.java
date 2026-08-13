package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.util.List;
import java.util.Locale;

/** CLIENT SETNAME/GETNAME：无会话态实现（ADR-0272，文档登记）。 */
public final class ClientCommand implements Command {

    @Override
    public String name() {
        return "client";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() < 1) {
            return RespError.wrongArity(name());
        }
        String sub = CommandUtil.text(args.get(0))
                .toLowerCase(Locale.ROOT);
        switch (sub) {
            case "setname" -> {
                if (args.size() != 2) {
                    return RespError.wrongArity(name());
                }
                return new RespSimpleString("OK");
            }
            case "getname" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name());
                }
                return RespNull.BULK_STRING;
            }
            default -> {
                return new RespError("ERR unknown subcommand '"
                        + sub + "'");
            }
        }
    }
}
