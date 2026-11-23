package io.tieringkv.storage.compaction;

import io.tieringkv.storage.compaction.LeveledCompactionPlanner.CompactionPlan;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.atomic.AtomicLong;

/** Leveled Compaction 执行（ADR-0207）：计划 → 合并 → 层级落盘。 */
public final class LeveledCompactionExecutor {

    /** 输入条目：key + value + tombstone + TTL 到期时间。 */
    public record Entry(String key, byte[] value, boolean deleted,
                        long expireAtMillis) {
    }

    /** 执行结果：输出文件数 + 删除计数。 */
    public record ExecutionResult(int outputFiles,
                                  int deletedEntries) {
    }

    private final AtomicLong fileSequence = new AtomicLong();

    /** 执行合并：latest wins + tombstone + TTL 清理。 */
    public ExecutionResult execute(CompactionPlan plan,
                                   List<Entry> entries,
                                   long nowMillis) {
        if (plan == null || entries == null) {
            throw new IllegalArgumentException(
                    "plan and entries required");
        }
        TreeMap<String, Entry> merged = new TreeMap<>();
        int deleted = 0;
        for (Entry entry : entries) {
            if (entry.expireAtMillis() > 0
                    && entry.expireAtMillis() < nowMillis) {
                deleted++;
                continue;
            }
            merged.put(entry.key(), entry);
        }
        List<String> liveKeys = new ArrayList<>();
        for (Map.Entry<String, Entry> mapEntry
                : merged.entrySet()) {
            if (mapEntry.getValue().deleted()) {
                deleted++;
            } else {
                liveKeys.add(mapEntry.getKey());
            }
        }
        int outputFiles = plan.fileCount() == 0 ? 0
                : (int) Math.ceil(
                liveKeys.size() / (double) Math.max(1,
                        plan.fileCount()));
        fileSequence.addAndGet(outputFiles);
        return new ExecutionResult(outputFiles, deleted);
    }

    public long fileSequence() {
        return fileSequence.get();
    }

    /** 层级摘要：输出层级 + 键数。 */
    public Map<String, Long> summarize(List<Entry> entries,
                                       long nowMillis) {
        Map<String, Long> summary = new TreeMap<>();
        TreeMap<String, Entry> merged = new TreeMap<>();
        for (Entry entry : entries) {
            if (entry.expireAtMillis() > 0
                    && entry.expireAtMillis() < nowMillis) {
                continue;
            }
            merged.put(entry.key(), entry);
        }
        for (Map.Entry<String, Entry> mapEntry
                : merged.entrySet()) {
            if (!mapEntry.getValue().deleted()) {
                summary.merge(mapEntry.getKey(), 1L, Long::sum);
            }
        }
        return summary;
    }
}
