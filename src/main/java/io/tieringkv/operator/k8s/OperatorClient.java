package io.tieringkv.operator.k8s;

/**
 * Operator 集群客户端抽象（ADR-0322 M4 增强）：隔离 fabric8 K8s 交互，
 * 便于单元测试（内存 fake）与真实集群（Fabric8OperatorClient）切换。
 */
public interface OperatorClient {

    /** 动作执行入口：把资源持久化/更新（生产接线到 StatefulSet 等）。 */
    void upsert(K8sTieringKVCluster resource);

    /** 回写 CR status。 */
    void updateStatus(K8sTieringKVCluster resource);
}
