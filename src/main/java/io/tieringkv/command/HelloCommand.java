package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.util.List;

/** HELLO [protover]：协议版本协商（ADR-0281，连接态接线 Phase 53）。 */
public final class HelloCommand implements Command {

    @Override
    public String name() {
        return "hello";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.size() > 1) {
            return RespError.wrongArity(name());
        }
        long version = 2;
        if (!args.isEmpty()) {
            try {
                version = CommandUtil.parseLong(args.get(0));
            } catch (NumberFormatException e) {
                return new RespError(CommandUtil.NOT_INTEGER);
            }
        }
        if (version != 2 && version != 3) {
            return new RespError("NOPROTO unsupported protocol "
                    + "version");
        }
        ConnectionContext context = ConnectionContext.current();
        if (context != null) {
            context.setVersion(version == 3
                    ? RespVersion.RESP3 : RespVersion.RESP2);
        }
        return new RespArray(List.of(
                new RespBulkString(CommandUtil.bytes("server")),
                new RespBulkString(CommandUtil.bytes(
                        "tiering-kv")),
                new RespBulkString(CommandUtil.bytes("version")),
                new RespBulkString(CommandUtil.bytes("3.4.0")),
                new RespBulkString(CommandUtil.bytes("proto")),
                new RespBulkString(CommandUtil.bytes(
                        Long.toString(version))),
                new RespBulkString(CommandUtil.bytes("mode")),
                new RespBulkString(CommandUtil.bytes("standalone"))));
    }
}
