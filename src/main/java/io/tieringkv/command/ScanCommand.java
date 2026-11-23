package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/** SCAN cursor [COUNT n] [MATCH pattern]：快照游标遍历（ADR-0272）。 */
public final class ScanCommand implements Command {

    /** 游标状态：有序键快照 + 已返回偏移 + 创建时间。 */
    private record ScanState(List<byte[]> keys, int offset, long createdAtMillis) {
    }

    private static final int MAX_STATES = 1024;
    private static final long STATE_TTL_MILLIS = 60_000L;
    private static final Map<Long, ScanState> STATES =
            new ConcurrentHashMap<>();
    private static final AtomicLong CURSOR = new AtomicLong();
    private static final int DEFAULT_COUNT = 10;

    @Override
    public String name() {
        return "scan";
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name());
        }
        try {
            long cursor = CommandUtil.parseLong(args.get(0));
            int count = DEFAULT_COUNT;
            String match = null;
            for (int i = 1; i + 1 < args.size(); i += 2) {
                String option = CommandUtil.text(args.get(i))
                        .toLowerCase(Locale.ROOT);
                if ("count".equals(option)) {
                    count = (int) CommandUtil.parseLong(
                            args.get(i + 1));
                } else if ("match".equals(option)) {
                    match = CommandUtil.text(args.get(i + 1));
                }
            }
            if (count <= 0) {
                count = DEFAULT_COUNT;
            }
            long now = System.currentTimeMillis();
            evictExpiredStates(now);
            List<byte[]> allKeys;
            int from;
            if (cursor == 0) {
                allKeys = snapshotKeys(storage);
                from = 0;
            } else {
                ScanState state = STATES.remove(cursor);
                if (state == null || now - state.createdAtMillis() > STATE_TTL_MILLIS) {
                    return new RespArray(List.of(
                            new RespBulkString(
                                    CommandUtil.bytes(0)),
                            new RespArray(List.of())));
                }
                allKeys = state.keys();
                from = state.offset();
            }
            Pattern compiled = match == null ? null : compileGlob(match);
            List<RespValue> keys = new ArrayList<>();
            int to = Math.min(allKeys.size(), from + count);
            for (int i = from; i < to; i++) {
                byte[] key = allKeys.get(i);
                if (compiled == null || globMatches(compiled, key)) {
                    keys.add(new RespBulkString(key));
                }
            }
            long next = 0;
            if (to < allKeys.size()) {
                long id = cursor == 0 ? CURSOR.incrementAndGet() : cursor;
                evictOldestIfFull();
                STATES.put(id, new ScanState(allKeys, to, now));
                next = id;
            }
            return new RespArray(List.of(
                    new RespBulkString(CommandUtil.bytes(next)),
                    new RespArray(keys)));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
        }
    }

    private static void evictExpiredStates(long now) {
        STATES.entrySet().removeIf(e -> now - e.getValue().createdAtMillis() > STATE_TTL_MILLIS);
    }

    private static void evictOldestIfFull() {
        while (STATES.size() >= MAX_STATES) {
            Long oldestKey = null;
            long oldestTs = Long.MAX_VALUE;
            for (Map.Entry<Long, ScanState> entry : STATES.entrySet()) {
                if (entry.getValue().createdAtMillis() < oldestTs) {
                    oldestTs = entry.getValue().createdAtMillis();
                    oldestKey = entry.getKey();
                }
            }
            if (oldestKey == null) {
                break;
            }
            STATES.remove(oldestKey);
        }
    }

    private static List<byte[]> snapshotKeys(
            StorageEngine storage) {
        List<byte[]> keys = new ArrayList<>();
        try (StorageIterator iterator = storage.iterator()) {
            while (iterator.hasNext()) {
                keys.add(iterator.next().key());
            }
        }
        return keys;
    }

    static Pattern compileGlob(String pattern) {
        return Pattern.compile(globToRegex(pattern), Pattern.DOTALL);
    }

    static boolean globMatches(Pattern compiled, byte[] key) {
        String text = new String(key,
                java.nio.charset.StandardCharsets.UTF_8);
        return compiled.matcher(text).matches();
    }

    static boolean globMatches(String pattern, byte[] key) {
        return globMatches(compileGlob(pattern), key);
    }

    static String globToRegex(String pattern) {
        StringBuilder builder = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> builder.append(".*");
                case '?' -> builder.append('.');
                default -> builder.append(Pattern.quote(
                        String.valueOf(c)));
            }
        }
        return builder.toString();
    }
}
