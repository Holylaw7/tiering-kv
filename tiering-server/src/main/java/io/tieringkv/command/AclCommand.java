package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.security.AclPolicy;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageEngine;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

/**
 * ACL 命令族：支持 WHOAMI / LIST / USERS / CAT / GETUSER / SETUSER / DELUSER。
 */
public final class AclCommand implements Command {

    private final AclPolicy aclPolicy;
    private final Supplier<List<String>> commandNames;

    public AclCommand() {
        this(new AclPolicy(), () -> List.of());
    }

    public AclCommand(AclPolicy aclPolicy, Supplier<List<String>> commandNames) {
        this.aclPolicy = Objects.requireNonNull(aclPolicy, "aclPolicy");
        this.commandNames = commandNames == null ? List::of : commandNames;
    }

    @Override
    public String name() {
        return "acl";
    }

    @Override
    public RespValue execute(List<byte[]> args, StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        String subcommand = text(args.get(0)).toLowerCase(Locale.ROOT);
        return switch (subcommand) {
            case "whoami" -> whoami(args);
            case "list" -> list(args);
            case "users" -> users(args);
            case "cat" -> cat(args);
            case "getuser" -> getuser(args);
            case "setuser" -> setuser(args);
            case "deluser" -> deluser(args);
            default -> new RespError("ERR Unknown ACL subcommand");
        };
    }

    private RespValue whoami(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        ConnectionContext context = ConnectionContext.current();
        String user = context != null && context.authenticatedUser() != null
                ? context.authenticatedUser()
                : AclPolicy.DEFAULT_USER;
        return bulk(user);
    }

    private RespValue list(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        List<RespValue> entries = new ArrayList<>();
        for (String line : aclPolicy.listDescriptions()) {
            entries.add(bulk(line));
        }
        return new RespArray(entries);
    }

    private RespValue users(List<byte[]> args) {
        if (args.size() != 1) {
            return RespError.wrongArity(name());
        }
        List<RespValue> entries = new ArrayList<>();
        for (String user : aclPolicy.usernames()) {
            entries.add(bulk(user));
        }
        return new RespArray(entries);
    }

    private RespValue cat(List<byte[]> args) {
        if (args.size() > 2) {
            return RespError.wrongArity(name());
        }
        if (args.size() == 2) {
            String category = text(args.get(1)).toLowerCase(Locale.ROOT);
            if (!aclPolicy.categories().contains(category)) {
                return new RespError("ERR Unknown category '" + category + "'");
            }
            List<RespValue> matched = new ArrayList<>();
            for (String cmd : aclPolicy.commandsInCategory(category, commandNames.get())) {
                matched.add(bulk(cmd));
            }
            return new RespArray(matched);
        }
        List<RespValue> categories = new ArrayList<>();
        aclPolicy.categories().stream().sorted().forEach(category -> categories.add(bulk(category)));
        return new RespArray(categories);
    }

    private RespValue getuser(List<byte[]> args) {
        if (args.size() != 2) {
            return RespError.wrongArity(name());
        }
        String username = text(args.get(1));
        List<String> summary = aclPolicy.userSummary(username);
        if (summary == null) {
            return new RespError("ERR User '" + username + "' not found");
        }
        List<RespValue> rules = new ArrayList<>();
        summary.forEach(rule -> rules.add(bulk(rule)));
        return new RespArray(rules);
    }

    private RespValue setuser(List<byte[]> args) {
        if (args.size() < 2) {
            return RespError.wrongArity(name());
        }
        String username = text(args.get(1));
        List<String> rules = new ArrayList<>(Math.max(0, args.size() - 2));
        for (int i = 2; i < args.size(); i++) {
            rules.add(text(args.get(i)));
        }
        try {
            aclPolicy.setUser(username, rules);
            return new RespSimpleString("OK");
        } catch (IllegalArgumentException ex) {
            return new RespError(ex.getMessage());
        }
    }

    private RespValue deluser(List<byte[]> args) {
        if (args.size() < 2) {
            return RespError.wrongArity(name());
        }
        List<String> names = new ArrayList<>(args.size() - 1);
        for (int i = 1; i < args.size(); i++) {
            names.add(text(args.get(i)));
        }
        try {
            return new RespInteger(aclPolicy.deleteUsers(names));
        } catch (IllegalArgumentException ex) {
            return new RespError(ex.getMessage());
        }
    }

    private static RespBulkString bulk(String value) {
        return new RespBulkString(value.getBytes(StandardCharsets.UTF_8));
    }

    private static String text(byte[] value) {
        return new String(value, StandardCharsets.UTF_8);
    }
}
