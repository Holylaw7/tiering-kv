package io.tieringkv.observability;

import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.atomic.AtomicLong;

/**
 * 命令慢查询日志（M4-C1）：有界环形缓冲 + 敏感命令参数自动脱敏（AUTH / ACL SETUSER / requirepass）。
 */
public final class SlowLog {

    public record Entry(long id, long timestampSeconds, long durationMicros, List<String> arguments) {
    }

    private final AtomicLong idSequence = new AtomicLong();
    private final Deque<Entry> entries = new ArrayDeque<>();
    private volatile long slowerThanMicros;
    private volatile int maxLen;

    public SlowLog(long slowerThanMicros, int maxLen) {
        this.slowerThanMicros = slowerThanMicros;
        this.maxLen = Math.max(1, maxLen);
    }

    public long slowerThanMicros() {
        return slowerThanMicros;
    }

    public void setSlowerThanMicros(long micros) {
        this.slowerThanMicros = micros;
    }

    public int maxLen() {
        return maxLen;
    }

    public void setMaxLen(int maxLen) {
        this.maxLen = Math.max(1, maxLen);
        synchronized (entries) {
            while (entries.size() > this.maxLen) {
                entries.removeLast();
            }
        }
    }

    /** 仅当启用（threshold >= 0）且耗时达到阈值时入队；忽略 SLOWLOG 自身查询以免污染记录。 */
    public void record(String commandName, List<byte[]> args, long durationNanos) {
        if ("slowlog".equalsIgnoreCase(commandName)) {
            return;
        }
        long threshold = this.slowerThanMicros;
        if (threshold < 0) {
            return;
        }
        long micros = durationNanos / 1_000L;
        if (micros < threshold) {
            return;
        }
        List<String> preview = sanitizeArgs(commandName, args);
        Entry entry = new Entry(
                idSequence.incrementAndGet(),
                System.currentTimeMillis() / 1_000L,
                micros,
                preview);
        synchronized (entries) {
            entries.addFirst(entry);
            while (entries.size() > maxLen) {
                entries.removeLast();
            }
        }
    }

    public List<Entry> latest(int count) {
        int limit = count <= 0 ? 10 : count;
        synchronized (entries) {
            List<Entry> out = new ArrayList<>(Math.min(limit, entries.size()));
            int idx = 0;
            for (Entry e : entries) {
                if (idx++ >= limit) {
                    break;
                }
                out.add(e);
            }
            return out;
        }
    }

    public int len() {
        synchronized (entries) {
            return entries.size();
        }
    }

    public void reset() {
        synchronized (entries) {
            entries.clear();
        }
    }

    private static List<String> sanitizeArgs(String commandName, List<byte[]> args) {
        String cmd = commandName == null ? "" : commandName.toLowerCase(Locale.ROOT);
        List<String> preview = new ArrayList<>(Math.min(1 + (args == null ? 0 : args.size()), 16));
        preview.add(cmd);
        if (args == null || args.isEmpty()) {
            return List.copyOf(preview);
        }
        boolean redactAllArgs = "auth".equals(cmd)
                || ("acl".equals(cmd) && args.size() > 0 && "setuser".equalsIgnoreCase(text(args.get(0))))
                || ("config".equals(cmd) && args.size() >= 2 && "requirepass".equalsIgnoreCase(text(args.get(1))));
        int limit = Math.min(args.size(), 15);
        boolean redactRest = false;
        for (int i = 0; i < limit; i++) {
            if ("auth".equals(cmd) || (redactAllArgs && i > 0) || redactRest) {
                preview.add("<redacted>");
            } else {
                String raw = text(args.get(i));
                preview.add(raw.length() > 128 ? raw.substring(0, 128) + "...(truncated)" : raw);
                if ("hello".equals(cmd) && "auth".equalsIgnoreCase(raw)) {
                    redactRest = true;
                }
            }
        }
        return List.copyOf(preview);
    }

    private static String text(byte[] bytes) {
        return bytes == null ? "" : new String(bytes, StandardCharsets.UTF_8);
    }
}
