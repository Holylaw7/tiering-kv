package io.tieringkv.storage.cold;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * Leveled compaction 策略（ADR-0323，TD-012）：内存 level 元数据，
 * 不改 SSTable 文件格式。
 *
 * <p>规则：新表进 L0；L0 超过 {@code l0MaxTables} 或 Ln 超过容量
 * （{@code tablesPerLevel << (level-1)}）→ 触发与下一级合并；
 * 读顺序：L0 新→旧，随后 L1..Ln（level 递增，新数据优先）。
 */
public final class LeveledCompaction {

    public static final int DEFAULT_L0_MAX_TABLES = 4;
    public static final int DEFAULT_TABLES_PER_LEVEL = 10;

    private final TreeMap<Integer, List<SSTableMeta>> levels =
            new TreeMap<>();
    private final int l0MaxTables;
    private final int tablesPerLevel;

    public LeveledCompaction() {
        this(DEFAULT_L0_MAX_TABLES, DEFAULT_TABLES_PER_LEVEL);
    }

    public LeveledCompaction(int l0MaxTables, int tablesPerLevel) {
        if (l0MaxTables < 1 || tablesPerLevel < 1) {
            throw new IllegalArgumentException(
                    "l0MaxTables/tablesPerLevel >= 1");
        }
        this.l0MaxTables = l0MaxTables;
        this.tablesPerLevel = tablesPerLevel;
    }

    /** 新表写入 L0（ColdStorageEngine.writeTable 后调用）。 */
    public void onTableWritten(SSTableMeta meta) {
        if (meta == null) {
            throw new IllegalArgumentException("meta required");
        }
        levels.computeIfAbsent(0, ignored -> new ArrayList<>())
                .add(meta);
    }

    public List<SSTableMeta> tablesAt(int level) {
        return List.copyOf(levels.getOrDefault(
                level, List.of()));
    }

    public int maxLevel() {
        return levels.isEmpty() ? 0 : levels.lastKey();
    }

    public int tableCount() {
        return levels.values().stream()
                .mapToInt(List::size).sum();
    }

    /** 读顺序：L0 新→旧，随后 L1..Ln（level 递增）。 */
    public List<SSTableMeta> orderForRead() {
        List<SSTableMeta> result = new ArrayList<>();
        List<SSTableMeta> l0 = levels.getOrDefault(0, List.of());
        for (int i = l0.size() - 1; i >= 0; i--) {
            result.add(l0.get(i));
        }
        for (int level = 1; level <= maxLevel(); level++) {
            result.addAll(levels.getOrDefault(level, List.of()));
        }
        return List.copyOf(result);
    }

    /** 返回需要合并的 level（-1 表示无需合并）。 */
    public int nextMergeLevel() {
        if (levels.getOrDefault(0, List.of()).size() > l0MaxTables) {
            return 0;
        }
        for (int level = 1; level <= maxLevel(); level++) {
            int capacity = tablesPerLevel << (level - 1);
            if (levels.getOrDefault(level, List.of()).size()
                    > capacity) {
                return level;
            }
        }
        return -1;
    }

    /** 合并完成：清空 fromLevel 输入，输出提升到 fromLevel+1。 */
    public void promote(int fromLevel, SSTableMeta mergedOutput) {
        if (mergedOutput == null) {
            throw new IllegalArgumentException(
                    "mergedOutput required");
        }
        List<SSTableMeta> from = levels.get(fromLevel);
        if (from != null) {
            from.clear();
        }
        levels.computeIfAbsent(fromLevel + 1,
                        ignored -> new ArrayList<>())
                .add(mergedOutput);
    }
}
