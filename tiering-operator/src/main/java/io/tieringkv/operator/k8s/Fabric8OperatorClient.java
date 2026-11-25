package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;

/**
 * fabric8 实现（ADR-0322 M4 / v5.4.0 A2 加固）：真实 K8s CRUD + 状态回写。
 *
 * <p>每次 {@link #upsert} 与 {@link #updateStatus} 均将 API Server 返回的最新
 * {@code resourceVersion} 同步回内存对象，且状态回写遇 {@code 409 Conflict}
 * 自动重拉重试，消除 Finalizer 写入后紧接状态更新导致的版本冲突。</p>
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
        K8sTieringKVCluster saved = client.resources(K8sTieringKVCluster.class)
                .resource(resource).createOrReplace();
        syncResourceVersion(resource, saved);
    }

    @Override
    public void updateStatus(K8sTieringKVCluster resource) {
        try {
            K8sTieringKVCluster saved = client.resources(K8sTieringKVCluster.class)
                    .resource(resource).updateStatus();
            syncResourceVersion(resource, saved);
        } catch (KubernetesClientException ex) {
            if (ex.getCode() != 409 || resource.getMetadata() == null) {
                throw ex;
            }
            String ns = resource.getMetadata().getNamespace();
            String name = resource.getMetadata().getName();
            K8sTieringKVCluster latest = (ns == null || ns.isBlank())
                    ? client.resources(K8sTieringKVCluster.class).withName(name).get()
                    : client.resources(K8sTieringKVCluster.class)
                            .inNamespace(ns).withName(name).get();
            if (latest == null) {
                throw ex;
            }
            latest.setStatus(resource.getStatus());
            K8sTieringKVCluster saved = client.resources(K8sTieringKVCluster.class)
                    .resource(latest).updateStatus();
            syncResourceVersion(resource, saved);
        }
    }

    private static void syncResourceVersion(K8sTieringKVCluster target,
                                            K8sTieringKVCluster saved) {
        if (target == null || saved == null
                || target.getMetadata() == null
                || saved.getMetadata() == null) {
            return;
        }
        String rv = saved.getMetadata().getResourceVersion();
        if (rv != null && !rv.isBlank()) {
            target.getMetadata().setResourceVersion(rv);
        }
    }
}
