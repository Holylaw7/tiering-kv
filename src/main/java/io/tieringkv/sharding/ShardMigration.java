package io.tieringkv.sharding;

import java.util.Map;
import java.util.function.Function;

/** 分片迁移（ADR-0126）：逐键迁移 + 校验。 */
public final class ShardMigration {

    private final Map<String, byte[]> source;
    private final Map<String, byte[]> target;

    public ShardMigration(Map<String, byte[]> source,
                          Map<String, byte[]> target) {
        this.source = source;
        this.target = target;
    }

    public int migrate(Function<String, Boolean> keySelector) {
        int moved = 0;
        for (Map.Entry<String, byte[]> entry :
                new java.util.ArrayList<>(source.entrySet())) {
            if (keySelector.apply(entry.getKey())) {
                target.put(entry.getKey(), entry.getValue());
                source.remove(entry.getKey());
                moved++;
            }
        }
        return moved;
    }

    public boolean verify() {
        for (Map.Entry<String, byte[]> entry : target.entrySet()) {
            if (!source.containsKey(entry.getKey())) {
                continue;
            }
            if (!java.util.Arrays.equals(source.get(entry.getKey()),
                    entry.getValue())) {
                return false;
            }
        }
        return true;
    }
}
