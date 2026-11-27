package io.tieringkv.execution;

import java.util.List;

/**
 * 可批聚合分片任务 SPI（v6.5.0 Phase 2）：
 * {@link ShardWorker} 在 {@code drainTo} 批量拉取后，将相邻且 {@link #batchKey()} 相同的
 * 可批任务合并为一次 {@link #runBatch(int, List)} 执行；遇任何普通任务（如 GET/INCR）立即切断并刷出，
 * 保证同键严格读己之所写（Read-Your-Own-Writes）线性一致性。
 */
public interface BatchableShardTask extends Runnable {

    /** 批次归属标识（同一执行引擎共享同一对象引用），避免混批不同处理器。 */
    Object batchKey();

    /**
     * 在当前分片线程上执行整段连续可批任务；调用方保证 {@code tasks.size() >= 1} 且 {@code tasks.get(0) == this}。
     */
    void runBatch(int shardId, List<BatchableShardTask> tasks);
}
