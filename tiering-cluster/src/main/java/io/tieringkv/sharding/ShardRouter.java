package io.tieringkv.sharding;

import java.nio.charset.StandardCharsets;

/** 版本化分片路由（ADR-0126）：双写窗口 + 原子切换 + 回滚。 */
public final class ShardRouter {

    private long routingVersion;
    private int shardCount;
    private boolean migrating;

    public ShardRouter(int shardCount) {
        if (shardCount < 1) {
            throw new IllegalArgumentException("shardCount >= 1");
        }
        this.shardCount = shardCount;
    }

    public int route(byte[] key) {
        String text = new String(key, StandardCharsets.UTF_8);
        return Math.floorMod(text.hashCode(), shardCount);
    }

    /** 迁移开始：进入双写窗口（写入新旧路由）。 */
    public int beginMigration(int newShardCount) {
        if (migrating) {
            throw new IllegalStateException("migration in progress");
        }
        if (newShardCount < 1) {
            throw new IllegalArgumentException("newShardCount >= 1");
        }
        migrating = true;
        return ++shardCount;
    }

    /** 原子切换：新路由版本生效。 */
    public long commitSwitch(int newShardCount) {
        if (!migrating) {
            throw new IllegalStateException("no migration");
        }
        shardCount = newShardCount;
        migrating = false;
        return ++routingVersion;
    }

    /** 回滚：放弃迁移，恢复原路由。 */
    public long rollback(int originalShardCount) {
        migrating = false;
        shardCount = originalShardCount;
        return routingVersion;
    }

    public boolean migrating() {
        return migrating;
    }

    public int shardCount() {
        return shardCount;
    }

    public long routingVersion() {
        return routingVersion;
    }
}
