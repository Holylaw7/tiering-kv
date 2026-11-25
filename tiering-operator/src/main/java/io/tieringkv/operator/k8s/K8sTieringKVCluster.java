package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.client.CustomResource;
import io.fabric8.kubernetes.model.annotation.Group;
import io.fabric8.kubernetes.model.annotation.Kind;
import io.fabric8.kubernetes.model.annotation.Plural;
import io.fabric8.kubernetes.model.annotation.Singular;
import io.fabric8.kubernetes.model.annotation.Version;

/** TieringKVCluster CRD 模型（ADR-0322 M4 / v5.4.0 A1 增强）。 */
@Group("tieringkv.io")
@Version("v1")
@Kind("TieringKVCluster")
@Plural("tieringkvclusters")
@Singular("tieringkvcluster")
public final class K8sTieringKVCluster
        extends CustomResource<K8sTieringKVClusterSpec,
        K8sTieringKVClusterStatus> {
}
