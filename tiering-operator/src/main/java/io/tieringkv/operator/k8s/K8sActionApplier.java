package io.tieringkv.operator.k8s;

import io.fabric8.kubernetes.api.model.ConfigMap;
import io.fabric8.kubernetes.api.model.ConfigMapBuilder;
import io.fabric8.kubernetes.api.model.ConfigMapVolumeSourceBuilder;
import io.fabric8.kubernetes.api.model.Container;
import io.fabric8.kubernetes.api.model.ContainerBuilder;
import io.fabric8.kubernetes.api.model.ContainerPortBuilder;
import io.fabric8.kubernetes.api.model.EnvVar;
import io.fabric8.kubernetes.api.model.EnvVarBuilder;
import io.fabric8.kubernetes.api.model.EnvVarSourceBuilder;
import io.fabric8.kubernetes.api.model.IntOrString;
import io.fabric8.kubernetes.api.model.LabelSelector;
import io.fabric8.kubernetes.api.model.LabelSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectFieldSelectorBuilder;
import io.fabric8.kubernetes.api.model.ObjectMeta;
import io.fabric8.kubernetes.api.model.ObjectMetaBuilder;
import io.fabric8.kubernetes.api.model.OwnerReference;
import io.fabric8.kubernetes.api.model.OwnerReferenceBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaim;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimBuilder;
import io.fabric8.kubernetes.api.model.PersistentVolumeClaimSpecBuilder;
import io.fabric8.kubernetes.api.model.PodSpecBuilder;
import io.fabric8.kubernetes.api.model.PodTemplateSpec;
import io.fabric8.kubernetes.api.model.PodTemplateSpecBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.Volume;
import io.fabric8.kubernetes.api.model.VolumeBuilder;
import io.fabric8.kubernetes.api.model.VolumeMount;
import io.fabric8.kubernetes.api.model.VolumeMountBuilder;
import io.fabric8.kubernetes.api.model.VolumeResourceRequirementsBuilder;
import io.fabric8.kubernetes.api.model.apps.Deployment;
import io.fabric8.kubernetes.api.model.apps.DeploymentBuilder;
import io.fabric8.kubernetes.api.model.apps.DeploymentSpecBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSet;
import io.fabric8.kubernetes.api.model.apps.StatefulSetBuilder;
import io.fabric8.kubernetes.api.model.apps.StatefulSetSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJob;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.CronJobSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobTemplateSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.tieringkv.operator.OperatorAction;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.StringJoiner;

/**
 * Applies reconciler actions to real Kubernetes resources.
 *
 * <p>Every resource is rebuilt from the CR spec and applied with
 * {@code createOrReplace}. This makes retries and informer duplicate events
 * idempotent while keeping the desired state in one place.</p>
 */
public final class K8sActionApplier implements ActionApplier {

    public static final String FINALIZER = "tieringkv.io/finalizer";

    private static final String API_VERSION = "tieringkv.io/v1";
    private static final String CONFIG_MOUNT = "/etc/tiering-kv";
    private static final String DATA_MOUNT = "/data";
    private static final String CONFIG_VOLUME = "config";
    private static final String DATA_VOLUME = "data";
    private static final String CONFIG_FILE = "tiering-kv.yaml";
    private static final String CONFIG_MAP_SUFFIX = "config";
    private static final String METADATA_SUFFIX = "meta";
    private static final String STORAGE_SUFFIX = "storage";
    private static final String GATEWAY_SUFFIX = "gateway";
    private static final String BACKUP_SUFFIX = "backup";
    private static final int DEFAULT_GATEWAY_REPLICAS = 1;
    private static final int METADATA_RPC_PORT = 7300;
    private static final int STORAGE_RPC_PORT = 7100;
    private static final int GATEWAY_RPC_PORT = 7201;
    private static final int REDIS_PORT = 6379;
    private static final int MAX_APPLY_ATTEMPTS = 3;
    private static final long RETRY_DELAY_MILLIS = 50;

    private final KubernetesClient client;

    public K8sActionApplier(KubernetesClient client) {
        if (client == null) {
            throw new IllegalArgumentException("client required");
        }
        this.client = client;
    }

    @Override
    public void apply(K8sTieringKVCluster resource, OperatorAction action) {
        validateResource(resource);
        if (action == null) {
            throw new IllegalArgumentException("action required");
        }
        switch (action.type()) {
            case CREATE, REPLACE_NODE, SCALE_UP, SCALE_DOWN, UPGRADE ->
                    ensureCluster(resource);
            case TRIGGER_BACKUP -> upsertBackup(resource);
            case DELETE -> deleteCluster(resource);
            case NOOP -> {
                // A NOOP deliberately avoids an API write.
            }
        }
    }

    @Override
    public K8sTieringKVClusterStatus observe(K8sTieringKVCluster resource) {
        validateResource(resource);
        String namespace = namespace(resource);
        K8sTieringKVClusterStatus current = resource.getStatus();
        K8sTieringKVClusterStatus observed =
                new K8sTieringKVClusterStatus();
        observed.setReadyMetadata(ready(client.apps().statefulSets()
                .inNamespace(namespace).withName(metadataName(resource))
                .get()));
        observed.setReadyStorage(ready(client.apps().statefulSets()
                .inNamespace(namespace).withName(storageName(resource))
                .get()));
        observed.setReadyGateway(ready(client.apps().deployments()
                .inNamespace(namespace).withName(gatewayName(resource))
                .get()));
        if (current != null) {
            observed.setObservedGeneration(current.getObservedGeneration());
            observed.setLastAction(current.getLastAction());
            observed.setPhase(current.getPhase());
        }
        return observed;
    }

    private void ensureCluster(K8sTieringKVCluster resource) {
        upsertConfigMap(configMap(resource));
        upsertStatefulSet(statefulSet(resource, true));
        upsertStatefulSet(statefulSet(resource, false));
        upsertService(service(resource, true, true));
        upsertService(service(resource, false, true));
        upsertService(service(resource, false, false));
        upsertDeployment(deployment(resource));
        if (hasBackupSchedule(resource)) {
            upsertBackup(resource);
        } else {
            deleteBackup(resource);
        }
    }

    private void upsertConfigMap(ConfigMap configMap) {
        retry(() -> client.configMaps().inNamespace(
                configMap.getMetadata().getNamespace())
                .resource(configMap).createOrReplace());
    }

    private void upsertStatefulSet(StatefulSet statefulSet) {
        retry(() -> client.apps().statefulSets().inNamespace(
                statefulSet.getMetadata().getNamespace())
                .resource(statefulSet).createOrReplace());
    }

    private void upsertDeployment(Deployment deployment) {
        retry(() -> client.apps().deployments().inNamespace(
                deployment.getMetadata().getNamespace())
                .resource(deployment).createOrReplace());
    }

    private void upsertService(Service service) {
        retry(() -> client.services().inNamespace(
                service.getMetadata().getNamespace())
                .resource(service).createOrReplace());
    }

    private void upsertBackup(K8sTieringKVCluster resource) {
        retry(() -> client.batch().v1().cronjobs()
                .inNamespace(namespace(resource))
                .resource(backup(resource)).createOrReplace());
    }

    private void deleteCluster(K8sTieringKVCluster resource) {
        String namespace = namespace(resource);
        deleteIfPresent(() -> client.batch().v1().cronjobs()
                .inNamespace(namespace)
                .withName(backupName(resource)).delete());
        deleteIfPresent(() -> client.apps().deployments().inNamespace(namespace)
                .withName(gatewayName(resource)).delete());
        deleteIfPresent(() -> client.services().inNamespace(namespace)
                .withName(gatewayName(resource)).delete());
        deleteIfPresent(() -> client.services().inNamespace(namespace)
                .withName(metadataName(resource)).delete());
        deleteIfPresent(() -> client.services().inNamespace(namespace)
                .withName(storageName(resource)).delete());
        deleteIfPresent(() -> client.apps().statefulSets().inNamespace(
                namespace).withName(metadataName(resource)).delete());
        deleteIfPresent(() -> client.apps().statefulSets().inNamespace(
                namespace).withName(storageName(resource)).delete());
        deleteIfPresent(() -> client.configMaps().inNamespace(namespace)
                .withName(configMapName(resource)).delete());
        // PVCs are intentionally retained; deleting a CR must not destroy data.
    }

    private void deleteBackup(K8sTieringKVCluster resource) {
        deleteIfPresent(() -> client.batch().v1().cronjobs().inNamespace(
                namespace(resource)).withName(backupName(resource)).delete());
    }

    private ConfigMap configMap(K8sTieringKVCluster resource) {
        Map<String, String> labels = labels(resource, "config");
        Map<String, String> data = new LinkedHashMap<>();
        data.put(CONFIG_FILE, runtimeConfig());
        data.put("regions.conf", regionsConfig(resource));
        data.put("start.sh", startScript());
        return new ConfigMapBuilder()
                .withApiVersion("v1")
                .withKind("ConfigMap")
                .withMetadata(metadata(configMapName(resource), resource,
                        labels))
                .withData(data)
                .build();
    }

    private StatefulSet statefulSet(K8sTieringKVCluster resource,
                                    boolean metadataRole) {
        String name = metadataRole ? metadataName(resource)
                : storageName(resource);
        String tier = metadataRole ? "metadata" : "storage";
        int replicas = metadataRole
                ? resource.getSpec().getMetadataReplicas()
                : resource.getSpec().getStorageReplicas();
        int rpcPort = metadataRole ? METADATA_RPC_PORT : STORAGE_RPC_PORT;
        Map<String, String> labels = labels(resource, tier);
        return new StatefulSetBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withMetadata(metadata(name, resource, labels))
                .withSpec(new StatefulSetSpecBuilder()
                        .withServiceName(name)
                        .withReplicas(replicas)
                        .withSelector(selector(labels))
                        .withTemplate(podTemplate(resource, tier,
                                rpcPort, false, true))
                        .withVolumeClaimTemplates(dataClaim(
                                metadataRole ? "10Gi" : "100Gi"))
                        .build())
                .build();
    }

    private Deployment deployment(K8sTieringKVCluster resource) {
        Map<String, String> labels = labels(resource, "gateway");
        return new DeploymentBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withMetadata(metadata(gatewayName(resource), resource,
                        labels))
                .withSpec(new DeploymentSpecBuilder()
                        .withReplicas(DEFAULT_GATEWAY_REPLICAS)
                        .withSelector(selector(labels))
                        .withTemplate(podTemplate(resource, "gateway",
                                GATEWAY_RPC_PORT, true, false))
                        .build())
                .build();
    }

    private Service service(K8sTieringKVCluster resource,
                            boolean metadataRole,
                            boolean headless) {
        String tier = metadataRole ? "metadata"
                : headless ? "storage" : "gateway";
        String name = switch (tier) {
            case "metadata" -> metadataName(resource);
            case "storage" -> storageName(resource);
            default -> gatewayName(resource);
        };
        int port = "metadata".equals(tier) ? METADATA_RPC_PORT
                : "storage".equals(tier) ? STORAGE_RPC_PORT : REDIS_PORT;
        Map<String, String> labels = labels(resource, tier);
        ServiceSpecBuilder spec = new ServiceSpecBuilder()
                .withSelector(labels)
                .withPorts(new ServicePortBuilder()
                        .withName("metadata".equals(tier)
                                || "storage".equals(tier)
                                ? "raft-rpc" : "redis")
                        .withPort(port)
                        .withTargetPort(new IntOrString(port))
                        .build());
        if (headless) {
            spec.withClusterIP("None");
        } else {
            spec.withType("ClusterIP");
        }
        return new ServiceBuilder()
                .withApiVersion("v1")
                .withKind("Service")
                .withMetadata(metadata(name, resource, labels))
                .withSpec(spec.build())
                .build();
    }

    private CronJob backup(K8sTieringKVCluster resource) {
        Map<String, String> labels = labels(resource, "backup");
        Container backupContainer = new ContainerBuilder()
                .withName("backup")
                .withImage(resource.getSpec().getImage())
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", "-c")
                .withArgs("echo backup executor is supplied by WP-B; "
                        + "cluster=$CLUSTER_NAME")
                .withEnv(new EnvVarBuilder().withName("CLUSTER_NAME")
                        .withValue(clusterName(resource)).build())
                .build();
        PodTemplateSpec pod = new PodTemplateSpecBuilder()
                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                .withSpec(new PodSpecBuilder()
                        .withRestartPolicy("OnFailure")
                        .withContainers(backupContainer)
                        .build())
                .build();
        JobTemplateSpecBuilder jobTemplate = new JobTemplateSpecBuilder()
                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                .withSpec(new JobSpecBuilder()
                        .withBackoffLimit(2)
                        .withTtlSecondsAfterFinished(86400)
                        .withTemplate(pod)
                        .build());
        return new CronJobBuilder()
                .withApiVersion("batch/v1")
                .withKind("CronJob")
                .withMetadata(metadata(backupName(resource), resource, labels))
                .withSpec(new CronJobSpecBuilder()
                        .withSchedule(resource.getSpec().getBackupScheduleCron())
                        .withConcurrencyPolicy("Forbid")
                        .withSuccessfulJobsHistoryLimit(3)
                        .withFailedJobsHistoryLimit(3)
                        .withSuspend(true)
                        .withJobTemplate(jobTemplate.build())
                        .build())
                .build();
    }

    private PodTemplateSpec podTemplate(K8sTieringKVCluster resource,
                                        String role,
                                        int rpcPort,
                                        boolean gateway,
                                        boolean withData) {
        Map<String, String> labels = labels(resource, role);
        List<VolumeMount> mounts = new ArrayList<>();
        mounts.add(new VolumeMountBuilder().withName(CONFIG_VOLUME)
                .withMountPath(CONFIG_MOUNT).build());
        if (withData) {
            mounts.add(new VolumeMountBuilder().withName(DATA_VOLUME)
                    .withMountPath(DATA_MOUNT).build());
        }
        List<EnvVar> env = new ArrayList<>();
        env.add(new EnvVarBuilder().withName("POD_NAME")
                .withValueFrom(new EnvVarSourceBuilder()
                        .withFieldRef(new ObjectFieldSelectorBuilder()
                                .withFieldPath("metadata.name").build())
                        .build()).build());
        env.add(new EnvVarBuilder().withName("ROLE")
                .withValue(gateway ? "gateway" : role).build());
        env.add(new EnvVarBuilder().withName("RPC_PORT")
                .withValue(String.valueOf(rpcPort)).build());
        if (withData) {
            env.add(new EnvVarBuilder().withName("DATA_DIR")
                    .withValue(DATA_MOUNT).build());
        }
        if (gateway) {
            env.add(new EnvVarBuilder().withName("GATEWAY_PORT")
                    .withValue(String.valueOf(REDIS_PORT)).build());
            env.add(new EnvVarBuilder().withName("METADATA_SERVICE")
                    .withValue(metadataName(resource)).build());
            env.add(new EnvVarBuilder().withName("METADATA_PORT")
                    .withValue(String.valueOf(METADATA_RPC_PORT)).build());
            env.add(new EnvVarBuilder().withName("REGIONS")
                    .withValue(regionEndpoints(resource)).build());
        }
        Container container = new ContainerBuilder()
                .withName(role)
                .withImage(resource.getSpec().getImage())
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/sh", CONFIG_MOUNT + "/start.sh")
                .withEnv(env)
                .withPorts(new ContainerPortBuilder()
                        .withName(gateway ? "redis" : "raft-rpc")
                        .withContainerPort(gateway ? REDIS_PORT : rpcPort)
                        .build())
                .withVolumeMounts(mounts)
                .build();
        List<Volume> volumes = List.of(new VolumeBuilder()
                .withName(CONFIG_VOLUME)
                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                        .withName(configMapName(resource)).build())
                .build());
        return new PodTemplateSpecBuilder()
                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                .withSpec(new PodSpecBuilder()
                        .withTerminationGracePeriodSeconds(gateway ? 30L : 60L)
                        .withContainers(container)
                        .withVolumes(volumes)
                        .build())
                .build();
    }

    private PersistentVolumeClaim dataClaim(String size) {
        return new PersistentVolumeClaimBuilder()
                .withApiVersion("v1")
                .withKind("PersistentVolumeClaim")
                .withMetadata(new ObjectMetaBuilder().withName(DATA_VOLUME)
                        .build())
                .withSpec(new PersistentVolumeClaimSpecBuilder()
                        .withAccessModes("ReadWriteOnce")
                        .withResources(new VolumeResourceRequirementsBuilder()
                                .addToRequests("storage",
                                        new io.fabric8.kubernetes.api.model.Quantity(
                                                size)).build())
                        .build())
                .build();
    }

    private ObjectMeta metadata(String name,
                                K8sTieringKVCluster resource,
                                Map<String, String> labels) {
        ObjectMetaBuilder builder = new ObjectMetaBuilder()
                .withName(name)
                .withNamespace(namespace(resource))
                .withLabels(labels);
        OwnerReference owner = ownerReference(resource);
        if (owner != null) {
            builder.withOwnerReferences(owner);
        }
        return builder.build();
    }

    private OwnerReference ownerReference(K8sTieringKVCluster resource) {
        String uid = resource.getMetadata().getUid();
        if (uid == null || uid.isBlank()) {
            return null;
        }
        return new OwnerReferenceBuilder()
                .withApiVersion(API_VERSION)
                .withKind("TieringKVCluster")
                .withName(clusterName(resource))
                .withUid(uid)
                .withController(true)
                .withBlockOwnerDeletion(true)
                .build();
    }

    private Map<String, String> labels(K8sTieringKVCluster resource,
                                       String tier) {
        Map<String, String> labels = new LinkedHashMap<>();
        labels.put("app", "tiering-kv");
        labels.put("tieringkv.io/cluster", clusterName(resource));
        labels.put("tier", tier);
        return labels;
    }

    private LabelSelector selector(Map<String, String> labels) {
        return new LabelSelectorBuilder().withMatchLabels(labels).build();
    }

    private String runtimeConfig() {
        return "server:\n"
                + "  gateway-port: 6379\n"
                + "  rpc-port: 7300\n"
                + "memory:\n"
                + "  memtable-bytes: 268435456\n"
                + "wal:\n"
                + "  fsync: everysec\n"
                + "  dir: /data/wal\n"
                + "raft:\n"
                + "  heartbeat-ms: 100\n"
                + "  election-min-ms: 80\n"
                + "  election-max-ms: 200\n";
    }

    private String regionsConfig(K8sTieringKVCluster resource) {
        StringBuilder regions = new StringBuilder();
        List<String> regionIds = resource.getSpec().getRegionIds();
        for (int i = 0; i < regionIds.size(); i++) {
            regions.append(storageName(resource)).append('-').append(i)
                    .append(' ').append(regionIds.get(i)).append('\n');
        }
        return regions.toString();
    }

    private String regionEndpoints(K8sTieringKVCluster resource) {
        StringJoiner endpoints = new StringJoiner(",");
        List<String> regionIds = resource.getSpec().getRegionIds();
        for (int i = 0; i < regionIds.size(); i++) {
            endpoints.add(regionIds.get(i) + "@" + storageName(resource)
                    + "-" + i + "." + storageName(resource) + ":"
                    + STORAGE_RPC_PORT);
        }
        return endpoints.toString();
    }

    private String startScript() {
        return "#!/bin/sh\n"
                + "set -e\n"
                + "ROLE=${ROLE:?missing ROLE}\n"
                + "NODE_ID=${POD_NAME:?missing POD_NAME}\n"
                + "RPC_PORT=${RPC_PORT:-7300}\n"
                + "DATA_DIR=${DATA_DIR:-/data}\n"
                + "case \"$ROLE\" in\n"
                + "  metadata)\n"
                + "    exec java -Xmx512m -cp /app/tiering-kv.jar "
                + "io.tieringkv.runtime.TxnRuntimeMain "
                + "--role metadata --node-id \"$NODE_ID\" "
                + "--rpc-port \"$RPC_PORT\" --data-dir \"$DATA_DIR\"\n"
                + "    ;;\n"
                + "  participant)\n"
                + "    REGION_ID=$(awk -v name=\"$NODE_ID\" '$1 == name {print $2}' "
                + "/etc/tiering-kv/regions.conf)\n"
                + "    if [ -z \"$REGION_ID\" ]; then REGION_ID=\"r${NODE_ID##*-}\"; fi\n"
                + "    exec java -Xmx1g -cp /app/tiering-kv.jar "
                + "io.tieringkv.runtime.TxnRuntimeMain "
                + "--role participant --node-id \"$NODE_ID\" "
                + "--region-id \"$REGION_ID\" --rpc-port \"$RPC_PORT\" "
                + "--data-dir \"$DATA_DIR\"\n"
                + "    ;;\n"
                + "  gateway)\n"
                + "    exec java -Xmx512m -cp /app/tiering-kv.jar "
                + "io.tieringkv.runtime.TxnRuntimeMain "
                + "--role gateway --node-id \"$NODE_ID\" "
                + "--rpc-port \"$RPC_PORT\" --gateway-port \"${GATEWAY_PORT:-6379}\" "
                + "--metadata-node \"$METADATA_SERVICE\" "
                + "--metadata-port \"${METADATA_PORT:-7300}\" "
                + "--regions \"$REGIONS\"\n"
                + "    ;;\n"
                + "  *) echo \"unknown role $ROLE\" >&2; exit 1 ;;\n"
                + "esac\n";
    }

    private static int ready(StatefulSet statefulSet) {
        if (statefulSet == null || statefulSet.getStatus() == null
                || statefulSet.getStatus().getReadyReplicas() == null) {
            return 0;
        }
        return statefulSet.getStatus().getReadyReplicas();
    }

    private static int ready(Deployment deployment) {
        if (deployment == null || deployment.getStatus() == null
                || deployment.getStatus().getReadyReplicas() == null) {
            return 0;
        }
        return deployment.getStatus().getReadyReplicas();
    }

    private static void validateResource(K8sTieringKVCluster resource) {
        if (resource == null || resource.getMetadata() == null
                || resource.getSpec() == null) {
            throw new IllegalArgumentException(
                    "resource metadata and spec required");
        }
        if (resource.getMetadata().getName() == null
                || resource.getMetadata().getName().isBlank()) {
            throw new IllegalArgumentException("resource name required");
        }
    }

    private static String namespace(K8sTieringKVCluster resource) {
        String namespace = resource.getMetadata().getNamespace();
        return namespace == null || namespace.isBlank() ? "default" : namespace;
    }

    private static String clusterName(K8sTieringKVCluster resource) {
        return resource.getMetadata().getName();
    }

    private static String metadataName(K8sTieringKVCluster resource) {
        return childName(resource, METADATA_SUFFIX);
    }

    private static String storageName(K8sTieringKVCluster resource) {
        return childName(resource, STORAGE_SUFFIX);
    }

    private static String gatewayName(K8sTieringKVCluster resource) {
        return childName(resource, GATEWAY_SUFFIX);
    }

    private static String configMapName(K8sTieringKVCluster resource) {
        return childName(resource, CONFIG_MAP_SUFFIX);
    }

    private static String backupName(K8sTieringKVCluster resource) {
        return childName(resource, BACKUP_SUFFIX);
    }

    private static String childName(K8sTieringKVCluster resource,
                                   String suffix) {
        String cluster = clusterName(resource);
        int maxClusterLength = 63 - suffix.length() - 1;
        if (cluster.length() > maxClusterLength) {
            cluster = cluster.substring(0, maxClusterLength);
        }
        return cluster + "-" + suffix;
    }

    private static boolean hasBackupSchedule(
            K8sTieringKVCluster resource) {
        String schedule = resource.getSpec().getBackupScheduleCron();
        return schedule != null && !schedule.isBlank();
    }

    private static void retry(Runnable operation) {
        RuntimeException last = null;
        for (int attempt = 1; attempt <= MAX_APPLY_ATTEMPTS; attempt++) {
            try {
                operation.run();
                return;
            } catch (RuntimeException failure) {
                last = failure;
                if (!retryable(failure) || attempt == MAX_APPLY_ATTEMPTS) {
                    throw failure;
                }
                try {
                    Thread.sleep(RETRY_DELAY_MILLIS * attempt);
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(
                            "interrupted while retrying Kubernetes write",
                            interrupted);
                }
            }
        }
        throw last;
    }

    private static boolean retryable(RuntimeException failure) {
        if (!(failure instanceof KubernetesClientException clientFailure)) {
            return false;
        }
        int code = clientFailure.getCode();
        return code == 409 || code >= 500;
    }

    private static void deleteIfPresent(Runnable operation) {
        operation.run();
    }
}
