package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.fabric8.kubernetes.client.dsl.Resource;

/**
 * fabric8 实现（ADR-0322 M4 / v5.4.0 加固）：真实 K8s 命名空间级 CRUD + 状态回写。
 *
 * <p>显式绑定命名空间；已有资源（含 {@code resourceVersion}）走 {@code update()}
 * 避免向只授权 {@code update/patch} 的最小权限 RBAC 发送 {@code POST create}；
 * 同步写回最新 {@code resourceVersion} 并在状态更新遇 {@code 409} 时自动重拉重试。</p>
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
        Resource<K8sTieringKVCluster> handle = resourceHandle(resource);
        boolean exists = resource.getMetadata() != null
                && resource.getMetadata().getResourceVersion() != null
                && !resource.getMetadata().getResourceVersion().isBlank();
        K8sTieringKVCluster saved = exists
                ? handle.replace()
                : handle.createOrReplace();
        syncResourceVersion(resource, saved);
    }

    @Override
    public void updateStatus(K8sTieringKVCluster resource) {
        try {
            K8sTieringKVCluster saved = resourceHandle(resource).updateStatus();
            syncResourceVersion(resource, saved);
        } catch (KubernetesClientException ex) {
            if (ex.getCode() != 409 || resource.getMetadata() == null) {
                throw ex;
            }
            String ns = namespace(resource);
            String name = resource.getMetadata().getName();
            K8sTieringKVCluster latest = client.resources(K8sTieringKVCluster.class)
                    .inNamespace(ns).withName(name).get();
            if (latest == null) {
                throw ex;
            }
            latest.setStatus(resource.getStatus());
            K8sTieringKVCluster saved = client.resources(K8sTieringKVCluster.class)
                    .inNamespace(ns).resource(latest).updateStatus();
            syncResourceVersion(resource, saved);
        }
    }

    private Resource<K8sTieringKVCluster> resourceHandle(K8sTieringKVCluster resource) {
        return client.resources(K8sTieringKVCluster.class)
                .inNamespace(namespace(resource))
                .resource(resource);
    }

    private static String namespace(K8sTieringKVCluster resource) {
        String ns = resource.getMetadata() == null
                ? null : resource.getMetadata().getNamespace();
        return ns == null || ns.isBlank() ? "default" : ns;
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
