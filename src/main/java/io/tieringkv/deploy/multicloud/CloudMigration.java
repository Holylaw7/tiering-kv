package io.tieringkv.deploy.multicloud;

import java.util.Map;

/** 跨环境数据搬迁（ADR-0136）：复用 ShardMigration 语义。 */
public final class CloudMigration {

    private final Map<String, byte[]> source;
    private final Map<String, byte[]> target;

    public CloudMigration(Map<String, byte[]> source,
                          Map<String, byte[]> target) {
        this.source = source;
        this.target = target;
    }

    public int migrate() {
        int moved = 0;
        for (Map.Entry<String, byte[]> entry :
                new java.util.ArrayList<>(source.entrySet())) {
            target.put(entry.getKey(), entry.getValue());
            source.remove(entry.getKey());
            moved++;
        }
        return moved;
    }

    public boolean verify() {
        return source.isEmpty();
    }
}
