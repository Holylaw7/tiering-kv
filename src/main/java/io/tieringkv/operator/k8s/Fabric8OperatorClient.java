package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;

/**
 * fabric8 实现（ADR-0322 M4 增强）：真实 K8s CRUD + 状态回写。
 * 动作执行（upsert）默认更新 CR 本身；底层工作负载接线由
 * ActionApplier/OperatorPlanner 动作驱动（M4 后扩展）。
 */
public final class Fabric8OperatorClient implements OperatorClient {

    private final KubernetesClient client;

    public Fabric8OperatorClient(KubernetesClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client required");
        }
        this.client = client;
    }

    @Override
    public void upsert(K8sTieringKVCluster resource) {
        client.resources(K8sTieringKVCluster.class)
                .resource(resource).createOrReplace();
    }

    @Override
    public void updateStatus(K8sTieringKVCluster resource) {
        client.resources(K8sTieringKVCluster.class)
                .resource(resource).updateStatus();
    }
}
