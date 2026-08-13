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

    /** 游标状态：有序键快照 + 已返回偏移。 */
    private record ScanState(List<byte[]> keys, int offset) {
    }

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
            ScanState state;
            if (cursor == 0) {
                long id = CURSOR.incrementAndGet();
                state = new ScanState(snapshotKeys(storage), 0);
                STATES.put(id, state);
                cursor = id;
            } else {
                state = STATES.get(cursor);
                if (state == null) {
                    return new RespArray(List.of(
                            new RespBulkString(
                                    CommandUtil.bytes(0)),
                            new RespArray(List.of())));
                }
            }
            List<RespValue> keys = new ArrayList<>();
            int from = state.offset();
            int to = Math.min(state.keys().size(), from + count);
            for (int i = from; i < to; i++) {
                byte[] key = state.keys().get(i);
                if (match == null || globMatches(match, key)) {
                    keys.add(new RespBulkString(key));
                }
            }
            long next = to < state.keys().size() ? cursor : 0;
            if (next == 0) {
                STATES.remove(cursor);
            } else {
                STATES.put(cursor, new ScanState(
                        state.keys(), to));
            }
            return new RespArray(List.of(
                    new RespBulkString(CommandUtil.bytes(next)),
                    new RespArray(keys)));
        } catch (NumberFormatException e) {
            return new RespError(CommandUtil.NOT_INTEGER);
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

    static boolean globMatches(String pattern, byte[] key) {
        String text = new String(key,
                java.nio.charset.StandardCharsets.UTF_8);
        return Pattern.compile(globToRegex(pattern))
                .matcher(text).matches();
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
