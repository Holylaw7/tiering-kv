package io.tieringkv.replication.cross;

import io.tieringkv.cdc.ChangeEvent;

/**
 * 跨集群冲突策略抽象（ADR-0333）：返回 true 表示事件应被应用。
 *
 * <p>调用方依赖接口而非具体实现；LWW 为当前实现，CRDT 等后续策略
 * 以新实现接入不改调用方。
 */
public interface ConflictResolver {

    /** 返回 true 表示该事件应被应用（比当前状态更新）。 */
    boolean accept(ChangeEvent event, String originClusterId);
}
