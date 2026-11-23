package io.tieringkv.storage;

/**
 * 向量索引写删观测回调（C2 模块解耦）：由存储装饰器喂入，可观测层实现。
 */
public interface VectorWriteMetrics {

    void recordVectorWrite();

    void recordVectorDelete();
}
