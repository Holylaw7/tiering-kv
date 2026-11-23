package io.tieringkv.observability;

/**
 * 复制滞后只读视图 SPI（C2 模块解耦）：由复制模块 {@code LagTracker} 实现，隔离服务层对集群复制层的编译依赖。
 */
public interface ReplicationLagView {

    int replicaCount();

    long maxLagMillis(long nowMillis);

    default void applied(String replicaId, long seq) {
        // 默认只读空操作
    }
}
