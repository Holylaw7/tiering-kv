package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespMap;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Locale;

/**
 * HELLO [protover [AUTH username password] [SETNAME clientname]]：
 * 协议版本协商并支持连接握手鉴权。
 */
public final class HelloCommand implements Command {

    private final AclPolicy aclPolicy;

    public HelloCommand() {
        this(null);
    }

    public HelloCommand(AclPolicy aclPolicy) {
        this.aclPolicy = aclPolicy;
    }

    @Override
    public String name() {
        return "hello";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        long version = 2;
        if (!args.isEmpty()) {
            try {
                version = CommandUtil.parseLong(args.get(0));
            } catch (NumberFormatException e) {
                return new RespError(CommandUtil.NOT_INTEGER);
            }
        }
        if (version != 2 && version != 3) {
            return new RespError("NOPROTO unsupported protocol version");
        }

        String authUser = null;
        String authPass = null;
        int idx = 1;
        while (idx < args.size()) {
            String option = new String(args.get(idx), StandardCharsets.UTF_8).toUpperCase(Locale.ROOT);
            if ("AUTH".equals(option)) {
                if (idx + 2 >= args.size()) {
                    return RespError.wrongArity(name());
                }
                authUser = new String(args.get(idx + 1), StandardCharsets.UTF_8);
                authPass = new String(args.get(idx + 2), StandardCharsets.UTF_8);
                idx += 3;
            } else if ("SETNAME".equals(option)) {
                if (idx + 1 >= args.size()) {
                    return RespError.wrongArity(name());
                }
                idx += 2;
            } else {
                return new RespError("ERR Syntax error in HELLO option '" + option + "'");
            }
        }

        ConnectionContext context = ConnectionContext.current();
        if (authUser != null) {
            if (aclPolicy != null && !aclPolicy.authenticate(authUser, authPass)) {
                return new RespError("WRONGPASS invalid username-password pair or user is disabled.");
            }
            if (context != null) {
                context.setAuthenticatedUser(authUser);
            }
        } else if (aclPolicy != null && aclPolicy.requiresAuthForAnonymous()
                && (context == null || context.authenticatedUser() == null)) {
            return new RespError("NOAUTH HELLO must be called with the client already authenticated, "
                    + "otherwise the HELLO AUTH <user> <pass> option can be used to authenticate "
                    + "the connection and select the RESP protocol version at the same time");
        }

        if (context != null) {
            context.setVersion(version == 3 ? RespVersion.RESP3 : RespVersion.RESP2);
        }
        List<RespValue> pairs = List.of(
                new RespBulkString(CommandUtil.bytes("server")),
                new RespBulkString(CommandUtil.bytes("tiering-kv")),
                new RespBulkString(CommandUtil.bytes("version")),
                new RespBulkString(CommandUtil.bytes("3.4.0")),
                new RespBulkString(CommandUtil.bytes("proto")),
                new RespBulkString(CommandUtil.bytes(Long.toString(version))),
                new RespBulkString(CommandUtil.bytes("mode")),
                new RespBulkString(CommandUtil.bytes("standalone")));
        if (context != null && context.version() == RespVersion.RESP3) {
            return new RespMap(pairs);
        }
        return new RespArray(pairs);
    }
}
