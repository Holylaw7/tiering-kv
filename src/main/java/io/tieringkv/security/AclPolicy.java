package io.tieringkv.security;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * RESP 访问控制策略（ACL）：默认 `default` 用户免密（兼容默认单机研发模式），
 * 一旦设置 requirepass / 非 nopass 规则即强制 AUTH，并执行命令类别与键前缀校验。
 */
public final class AclPolicy {

    public static final String DEFAULT_USER = "default";

    private static final Set<String> CATEGORIES = Set.of(
            "generic", "string", "list", "set", "zset", "hash",
            "stream", "pubsub", "transaction", "connection", "server",
            "scripting", "json", "timeseries", "vector", "bit", "geo",
            "read", "write", "admin", "dangerous");

    private static final Set<String> PUBLIC_PRE_AUTH = Set.of(
            "auth", "hello", "quit", "reset");

    private static final Set<String> PUBSUB_COMMANDS = Set.of(
            "publish", "subscribe", "unsubscribe", "psubscribe", "punsubscribe");

    private static final Set<String> ADMIN_COMMANDS = Set.of(
            "acl", "config", "client", "command", "info", "slowlog",
            "flushdb", "flushall", "shutdown",
            "script", "eval", "evalsha", "vector.checkpoint");

    private static final Set<String> DANGEROUS_COMMANDS = Set.of(
            "acl", "config", "client", "flushdb", "flushall",
            "shutdown", "script", "eval", "evalsha", "keys");

    private static final Set<String> CONNECTION_COMMANDS = Set.of(
            "auth", "hello", "ping", "echo", "quit", "reset", "client");

    private static final Set<String> TRANSACTION_COMMANDS = Set.of(
            "multi", "exec", "discard", "watch", "unwatch");

    private static final Set<String> KEYLESS_COMMANDS = Set.of(
            "ping", "echo", "auth", "hello", "quit", "reset",
            "info", "dbsize", "flushdb", "flushall", "scan", "keys",
            "config", "client", "command", "acl", "script",
            "eval", "evalsha", "multi", "exec", "discard", "unwatch",
            "publish", "subscribe", "unsubscribe", "psubscribe", "punsubscribe",
            "vector.list", "vector.checkpoint");

    private static final Set<String> MULTI_KEY_ALL = Set.of(
            "del", "unlink", "exists", "mget", "sinter", "sunion", "sdiff",
            "sinterstore", "sunionstore", "sdiffstore");

    private static final Set<String> PAIR_KEY_COMMANDS = Set.of(
            "mset", "msetnx");

    private static final Set<String> READONLY_COMMANDS = Set.of(
            "ping", "echo", "get", "exists", "strlen", "getrange", "ttl", "pttl",
            "mget", "dbsize", "scan", "type", "keys", "info", "command", "hello", "auth",
            "hget", "hexists", "hlen", "hkeys", "hvals", "hgetall", "hmget", "hscan",
            "llen", "lrange", "lindex",
            "scard", "smembers", "sismember", "srandmember", "sinter", "sunion", "sdiff",
            "zscore", "zrange", "zrevrange", "zcard", "zrangebyscore", "zcount",
            "zrank", "zrevrank", "zrangebylex", "zlexcount",
            "xlen", "xrange", "xread", "xpending",
            "getbit", "bitcount", "bitpos",
            "geopos", "geodist", "geohash", "geosearch", "georadius", "georadiusbymember",
            "object", "json.get", "json.type", "json.arrlen", "json.objkeys", "json.objlen",
            "json.strlen", "ts.get", "ts.len", "ts.range", "ts.mrange",
            "vector.search", "vector.len", "vector.list", "vector.get");

    private final ConcurrentHashMap<String, UserRule> users = new ConcurrentHashMap<>();
    private final Object mutationLock = new Object();

    public AclPolicy() {
        users.put(DEFAULT_USER, UserRule.defaultPasswordless());
    }

    public boolean requiresAuthForAnonymous() {
        UserRule def = users.get(DEFAULT_USER);
        return def == null || !def.enabled() || !def.nopass();
    }

    /** 配置兼容 requirepass：非空时取消 default 的 nopass 并启用强口令校验。 */
    public void configureRequirePass(String password) {
        synchronized (mutationLock) {
            UserRule current = users.getOrDefault(DEFAULT_USER, UserRule.defaultPasswordless());
            if (password == null || password.isBlank()) {
                users.put(DEFAULT_USER, current.withNopass(true, Set.of()));
            } else {
                users.put(DEFAULT_USER, current.withNopass(false, Set.of(sha256Hex(password))));
            }
        }
    }

    /** 校验用户名/密码，防时序侧信道。 */
    public boolean authenticate(String username, String password) {
        if (username == null || username.isBlank() || password == null) {
            return false;
        }
        UserRule user = users.get(username);
        if (user == null || !user.enabled()) {
            return false;
        }
        if (user.nopass()) {
            return true;
        }
        byte[] candidate = sha256Hex(password).getBytes(StandardCharsets.US_ASCII);
        for (String expected : user.passwordHashes()) {
            if (MessageDigest.isEqual(candidate, expected.getBytes(StandardCharsets.US_ASCII))) {
                return true;
            }
        }
        return false;
    }

    /** 返回 null 表示允许；否则返回 RESP 标准错误文本（不含前导 '-'）。 */
    public String authorize(String authenticatedUser, String commandName, List<byte[]> args) {
        String cmd = commandName == null ? "" : commandName.toLowerCase(Locale.ROOT);
        if (authenticatedUser == null || authenticatedUser.isBlank()) {
            if (!requiresAuthForAnonymous()) {
                return checkUserPermissions(users.get(DEFAULT_USER), cmd, args);
            }
            if (PUBLIC_PRE_AUTH.contains(cmd)) {
                return null;
            }
            return "NOAUTH Authentication required.";
        }
        UserRule user = users.get(authenticatedUser);
        if (user == null || !user.enabled()) {
            return "NOAUTH Authentication required.";
        }
        return checkUserPermissions(user, cmd, args);
    }

    private String checkUserPermissions(UserRule user, String cmd, List<byte[]> args) {
        if (user == null || !user.enabled()) {
            return "NOPERM user is disabled or missing";
        }
        if (!PUBLIC_PRE_AUTH.contains(cmd) && !user.allowsCommand(cmd)) {
            return "NOPERM this user has no permissions to run the '" + cmd + "' command";
        }
        if (!user.allKeys() && !KEYLESS_COMMANDS.contains(cmd)) {
            for (byte[] key : extractKeys(cmd, args)) {
                if (!user.allowsKey(key)) {
                    return "NOPERM this user has no permissions to access one of the keys used as arguments";
                }
            }
        }
        return null;
    }

    /** 应用 ACL SETUSER 规则，语义失败抛 IllegalArgumentException。 */
    public void setUser(String username, List<String> rules) {
        if (username == null || username.isBlank()) {
            throw new IllegalArgumentException("ERR invalid username");
        }
        synchronized (mutationLock) {
            UserRule existing = users.get(username);
            MutableRule builder = existing == null
                    ? MutableRule.emptyDisabled()
                    : existing.toMutable();
            for (String rawRule : rules) {
                applyRule(builder, rawRule);
            }
            users.put(username, builder.freeze());
        }
    }

    public long deleteUsers(List<String> usernames) {
        synchronized (mutationLock) {
            for (String name : usernames) {
                if (DEFAULT_USER.equals(name)) {
                    throw new IllegalArgumentException("ERR The 'default' user cannot be removed");
                }
            }
            long removed = 0;
            for (String name : usernames) {
                if (users.remove(name) != null) {
                    removed++;
                }
            }
            return removed;
        }
    }

    public List<String> usernames() {
        List<String> names = new ArrayList<>(users.keySet());
        Collections.sort(names);
        return names;
    }

    public List<String> listDescriptions() {
        List<String> out = new ArrayList<>();
        for (String name : usernames()) {
            UserRule user = users.get(name);
            if (user != null) {
                out.add("user " + name + " " + user.formatLine());
            }
        }
        return out;
    }

    public List<String> userSummary(String username) {
        UserRule user = users.get(username);
        return user == null ? null : user.formatTokens();
    }

    public boolean hasUser(String username) {
        return users.containsKey(username);
    }

    public Set<String> categories() {
        return CATEGORIES;
    }

    public List<String> commandsInCategory(String category, List<String> allCommandNames) {
        String cat = category.toLowerCase(Locale.ROOT);
        List<String> matched = new ArrayList<>();
        for (String name : allCommandNames) {
            if (matchesCategory(name.toLowerCase(Locale.ROOT), cat)) {
                matched.add(name);
            }
        }
        Collections.sort(matched);
        return matched;
    }

    private static void applyRule(MutableRule m, String rule) {
        if (rule == null || rule.isEmpty()) {
            throw new IllegalArgumentException("ERR Empty ACL rule");
        }
        String lower = rule.toLowerCase(Locale.ROOT);
        switch (lower) {
            case "on" -> m.enabled = true;
            case "off" -> m.enabled = false;
            case "nopass" -> {
                m.nopass = true;
                m.passwordHashes.clear();
            }
            case "resetpass" -> {
                m.nopass = false;
                m.passwordHashes.clear();
            }
            case "allkeys", "~*" -> {
                m.allKeys = true;
                m.keyPatterns.clear();
            }
            case "resetkeys" -> {
                m.allKeys = false;
                m.keyPatterns.clear();
            }
            case "allcommands", "+@all" -> {
                m.allCommands = true;
                m.allowedCategories.clear();
                m.deniedCategories.clear();
                m.allowedCommands.clear();
                m.deniedCommands.clear();
            }
            case "nocommands", "-@all" -> {
                m.allCommands = false;
                m.allowedCategories.clear();
                m.deniedCategories.clear();
                m.allowedCommands.clear();
                m.deniedCommands.clear();
            }
            case "&*", "resetchannels" -> {
                // 频道权限兼容占位
            }
            case "reset" -> m.reset();
            default -> applyPrefixRule(m, rule, lower);
        }
    }

    private static void applyPrefixRule(MutableRule m, String rule, String lower) {
        if (rule.startsWith(">")) {
            if (rule.length() < 2) {
                throw new IllegalArgumentException("ERR Password cannot be empty");
            }
            m.nopass = false;
            m.passwordHashes.add(sha256Hex(rule.substring(1)));
            return;
        }
        if (rule.startsWith("<")) {
            String hash = sha256Hex(rule.substring(1));
            m.passwordHashes.remove(hash);
            return;
        }
        if (rule.startsWith("#")) {
            String hex = rule.substring(1).toLowerCase(Locale.ROOT);
            if (!hex.matches("[0-9a-f]{64}")) {
                throw new IllegalArgumentException("ERR Invalid SHA-256 hash");
            }
            m.nopass = false;
            m.passwordHashes.add(hex);
            return;
        }
        if (rule.startsWith("~")) {
            String pat = rule.substring(1);
            if (pat.isEmpty()) {
                throw new IllegalArgumentException("ERR Empty key pattern");
            }
            if ("*".equals(pat)) {
                m.allKeys = true;
                m.keyPatterns.clear();
            } else if (!m.allKeys) {
                m.keyPatterns.add(pat);
            }
            return;
        }
        if (lower.startsWith("+@")) {
            String cat = lower.substring(2);
            if (!CATEGORIES.contains(cat)) {
                throw new IllegalArgumentException("ERR Unknown ACL category '" + cat + "'");
            }
            m.deniedCategories.remove(cat);
            m.allowedCategories.add(cat);
            return;
        }
        if (lower.startsWith("-@")) {
            String cat = lower.substring(2);
            if (!CATEGORIES.contains(cat)) {
                throw new IllegalArgumentException("ERR Unknown ACL category '" + cat + "'");
            }
            m.allowedCategories.remove(cat);
            m.deniedCategories.add(cat);
            return;
        }
        if (lower.startsWith("+") && lower.length() > 1) {
            String cmd = lower.substring(1);
            m.deniedCommands.remove(cmd);
            m.allowedCommands.add(cmd);
            return;
        }
        if (lower.startsWith("-") && lower.length() > 1) {
            String cmd = lower.substring(1);
            m.allowedCommands.remove(cmd);
            m.deniedCommands.add(cmd);
            return;
        }
        throw new IllegalArgumentException("ERR Syntax error in ACL SETUSER modifier '" + rule + "'");
    }

    static boolean matchesCategory(String cmd, String category) {
        return switch (category) {
            case "all" -> true;
            case "read" -> READONLY_COMMANDS.contains(cmd);
            case "write" -> !READONLY_COMMANDS.contains(cmd)
                    && !ADMIN_COMMANDS.contains(cmd)
                    && !TRANSACTION_COMMANDS.contains(cmd)
                    && !CONNECTION_COMMANDS.contains(cmd)
                    && !PUBSUB_COMMANDS.contains(cmd);
            case "admin" -> ADMIN_COMMANDS.contains(cmd);
            case "dangerous" -> DANGEROUS_COMMANDS.contains(cmd);
            case "connection" -> CONNECTION_COMMANDS.contains(cmd);
            case "transaction" -> TRANSACTION_COMMANDS.contains(cmd);
            case "pubsub" -> PUBSUB_COMMANDS.contains(cmd);
            case "scripting" -> Set.of("script", "eval", "evalsha").contains(cmd);
            case "json" -> cmd.startsWith("json.");
            case "timeseries" -> cmd.startsWith("ts.");
            case "vector" -> cmd.startsWith("vector.");
            case "hash" -> cmd.startsWith("h") && !"hello".equals(cmd);
            case "list" -> Set.of("lpush", "rpush", "lpop", "rpop", "llen", "lrange",
                    "lindex", "lset", "lrem", "ltrim", "linsert", "lmove", "rpoplpush", "blpop", "brpop").contains(cmd);
            case "set" -> Set.of("sadd", "srem", "sismember", "scard", "smembers", "spop",
                    "srandmember", "sinter", "sunion", "sdiff",
                    "sinterstore", "sunionstore", "sdiffstore").contains(cmd);
            case "zset" -> cmd.startsWith("z");
            case "stream" -> cmd.startsWith("x");
            case "bit" -> Set.of("setbit", "getbit", "bitcount", "bitpos", "bitop").contains(cmd);
            case "geo" -> cmd.startsWith("geo");
            case "server" -> ADMIN_COMMANDS.contains(cmd) || Set.of("dbsize", "save").contains(cmd);
            case "generic" -> Set.of("del", "unlink", "exists", "expire", "pexpire",
                    "expireat", "pexpireat", "persist", "ttl", "pttl", "type", "scan", "keys", "object").contains(cmd);
            case "string" -> Set.of("set", "get", "incr", "decr", "incrby", "decrby", "append",
                    "strlen", "getset", "setnx", "setex", "psetex", "getdel", "getrange", "setrange",
                    "mget", "mset", "msetnx").contains(cmd);
            default -> false;
        };
    }

    private static List<byte[]> extractKeys(String cmd, List<byte[]> args) {
        if (args == null || args.isEmpty()) {
            return List.of();
        }
        if (MULTI_KEY_ALL.contains(cmd)) {
            return args;
        }
        if (PAIR_KEY_COMMANDS.contains(cmd)) {
            List<byte[]> keys = new ArrayList<>((args.size() + 1) / 2);
            for (int i = 0; i < args.size(); i += 2) {
                keys.add(args.get(i));
            }
            return keys;
        }
        if ("blpop".equals(cmd) || "brpop".equals(cmd)) {
            return args.size() <= 1 ? List.of() : args.subList(0, args.size() - 1);
        }
        if ("lmove".equals(cmd) || "rpoplpush".equals(cmd)) {
            return args.size() >= 2 ? List.of(args.get(0), args.get(1)) : List.of(args.get(0));
        }
        if ("bitop".equals(cmd)) {
            return args.size() <= 1 ? List.of() : args.subList(1, args.size());
        }
        if ("geosearch".equals(cmd)) {
            return List.of(args.get(0));
        }
        if ("eval".equals(cmd) || "evalsha".equals(cmd) || "xread".equals(cmd) || "xreadgroup".equals(cmd)) {
            // 动态多键声明暂不截断，保留命令类别授权边界
            return List.of();
        }
        return List.of(args.get(0));
    }

    static boolean globMatches(String pattern, byte[] keyBytes) {
        String key = new String(keyBytes, StandardCharsets.UTF_8);
        return globMatchString(pattern, 0, key, 0);
    }

    private static boolean globMatchString(String p, int pi, String s, int si) {
        int starIdx = -1;
        int matchIdx = 0;
        while (si < s.length()) {
            if (pi < p.length() && (p.charAt(pi) == '?' || p.charAt(pi) == s.charAt(si))) {
                pi++;
                si++;
            } else if (pi < p.length() && p.charAt(pi) == '*') {
                starIdx = pi++;
                matchIdx = si;
            } else if (starIdx != -1) {
                pi = starIdx + 1;
                si = ++matchIdx;
            } else {
                return false;
            }
        }
        while (pi < p.length() && p.charAt(pi) == '*') {
            pi++;
        }
        return pi == p.length();
    }

    static String sha256Hex(String raw) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest(raw.getBytes(StandardCharsets.UTF_8));
            StringBuilder sb = new StringBuilder(digest.length * 2);
            for (byte b : digest) {
                sb.append(Character.forDigit((b >> 4) & 0xf, 16));
                sb.append(Character.forDigit(b & 0xf, 16));
            }
            return sb.toString();
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 unavailable", e);
        }
    }

    private record UserRule(
            boolean enabled,
            boolean nopass,
            Set<String> passwordHashes,
            boolean allKeys,
            Set<String> keyPatterns,
            boolean allCommands,
            Set<String> allowedCategories,
            Set<String> deniedCategories,
            Set<String> allowedCommands,
            Set<String> deniedCommands) {

        static UserRule defaultPasswordless() {
            return new UserRule(true, true, Set.of(), true, Set.of(), true,
                    Set.of(), Set.of(), Set.of(), Set.of());
        }

        UserRule withNopass(boolean nextNopass, Set<String> nextHashes) {
            return new UserRule(enabled, nextNopass, Set.copyOf(nextHashes),
                    allKeys, keyPatterns, allCommands,
                    allowedCategories, deniedCategories, allowedCommands, deniedCommands);
        }

        MutableRule toMutable() {
            MutableRule m = new MutableRule();
            m.enabled = enabled;
            m.nopass = nopass;
            m.passwordHashes.addAll(passwordHashes);
            m.allKeys = allKeys;
            m.keyPatterns.addAll(keyPatterns);
            m.allCommands = allCommands;
            m.allowedCategories.addAll(allowedCategories);
            m.deniedCategories.addAll(deniedCategories);
            m.allowedCommands.addAll(allowedCommands);
            m.deniedCommands.addAll(deniedCommands);
            return m;
        }

        boolean allowsCommand(String cmd) {
            if (deniedCommands.contains(cmd)) {
                return false;
            }
            if (allowedCommands.contains(cmd)) {
                return true;
            }
            boolean allowed = allCommands;
            if (!allowed) {
                for (String cat : allowedCategories) {
                    if (matchesCategory(cmd, cat)) {
                        allowed = true;
                        break;
                    }
                }
            }
            if (!allowed) {
                return false;
            }
            for (String deniedCat : deniedCategories) {
                if (matchesCategory(cmd, deniedCat)) {
                    return false;
                }
            }
            return true;
        }

        boolean allowsKey(byte[] key) {
            if (allKeys) {
                return true;
            }
            for (String pattern : keyPatterns) {
                if (globMatches(pattern, key)) {
                    return true;
                }
            }
            return false;
        }

        List<String> formatTokens() {
            List<String> tokens = new ArrayList<>();
            tokens.add(enabled ? "on" : "off");
            if (nopass) {
                tokens.add("nopass");
            } else {
                for (String hash : passwordHashes) {
                    tokens.add("#" + hash);
                }
            }
            if (allKeys) {
                tokens.add("~*");
            } else {
                for (String p : keyPatterns) {
                    tokens.add("~" + p);
                }
            }
            tokens.add("&*");
            tokens.add(formatCommandsToken());
            return tokens;
        }

        String formatLine() {
            return String.join(" ", formatTokens());
        }

        private String formatCommandsToken() {
            if (allCommands && deniedCategories.isEmpty() && deniedCommands.isEmpty()) {
                return "+@all";
            }
            List<String> parts = new ArrayList<>();
            parts.add(allCommands ? "+@all" : "-@all");
            for (String cat : allowedCategories) {
                parts.add("+@" + cat);
            }
            for (String cmd : allowedCommands) {
                parts.add("+" + cmd);
            }
            for (String cat : deniedCategories) {
                parts.add("-@" + cat);
            }
            for (String cmd : deniedCommands) {
                parts.add("-" + cmd);
            }
            return String.join(" ", parts);
        }
    }

    private static final class MutableRule {
        boolean enabled;
        boolean nopass;
        final Set<String> passwordHashes = new LinkedHashSet<>();
        boolean allKeys;
        final Set<String> keyPatterns = new LinkedHashSet<>();
        boolean allCommands;
        final Set<String> allowedCategories = new LinkedHashSet<>();
        final Set<String> deniedCategories = new LinkedHashSet<>();
        final Set<String> allowedCommands = new LinkedHashSet<>();
        final Set<String> deniedCommands = new LinkedHashSet<>();

        static MutableRule emptyDisabled() {
            return new MutableRule();
        }

        void reset() {
            enabled = false;
            nopass = false;
            passwordHashes.clear();
            allKeys = false;
            keyPatterns.clear();
            allCommands = false;
            allowedCategories.clear();
            deniedCategories.clear();
            allowedCommands.clear();
            deniedCommands.clear();
        }

        UserRule freeze() {
            return new UserRule(enabled, nopass, Set.copyOf(passwordHashes),
                    allKeys, Set.copyOf(keyPatterns), allCommands,
                    Set.copyOf(allowedCategories), Set.copyOf(deniedCategories),
                    Set.copyOf(allowedCommands), Set.copyOf(deniedCommands));
        }
    }
}
