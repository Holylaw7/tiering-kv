package io.tieringkv.command;

import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Objects;

/**
 * AUTH [username] password：绑定当前连接上下文的已认证主体。
 */
public final class AuthCommand implements Command {

    private final AclPolicy aclPolicy;

    public AuthCommand(AclPolicy aclPolicy) {
        this.aclPolicy = Objects.requireNonNull(aclPolicy, "aclPolicy");
    }

    @Override
    public String name() {
        return "auth";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.size() != 1 && args.size() != 2) {
            return RespError.wrongArity(name());
        }
        String username = args.size() == 1
                ? AclPolicy.DEFAULT_USER
                : new String(args.get(0), StandardCharsets.UTF_8);
        String password = new String(args.get(args.size() - 1), StandardCharsets.UTF_8);
        if (!aclPolicy.authenticate(username, password)) {
            return new RespError("WRONGPASS invalid username-password pair or user is disabled.");
        }
        ConnectionContext context = ConnectionContext.current();
        if (context != null) {
            context.setAuthenticatedUser(username);
        }
        return new RespSimpleString("OK");
    }
}
