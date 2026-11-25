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
import io.fabric8.kubernetes.api.model.Probe;
import io.fabric8.kubernetes.api.model.ProbeBuilder;
import io.fabric8.kubernetes.api.model.Service;
import io.fabric8.kubernetes.api.model.ServiceBuilder;
import io.fabric8.kubernetes.api.model.ServicePortBuilder;
import io.fabric8.kubernetes.api.model.ServiceSpecBuilder;
import io.fabric8.kubernetes.api.model.TCPSocketActionBuilder;
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
import io.fabric8.kubernetes.api.model.batch.v1.Job;
import io.fabric8.kubernetes.api.model.batch.v1.JobBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobSpecBuilder;
import io.fabric8.kubernetes.api.model.batch.v1.JobStatus;
import io.fabric8.kubernetes.api.model.batch.v1.JobTemplateSpecBuilder;
import io.fabric8.kubernetes.client.KubernetesClient;
import io.fabric8.kubernetes.client.KubernetesClientException;
import io.tieringkv.operator.OperatorAction;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.StringJoiner;

/**
 * Applies reconciler actions to real Kubernetes resources (v5.4.0 A1 hardened).
 *
 * <p>Uses immutable-field-safe updates on {@link StatefulSet} (patching only
 * mutable {@code replicas} and {@code template}, never mutating
 * {@code volumeClaimTemplates}) and {@link Service} (preserving assigned
 * {@code clusterIP}), wires real TCP probes, fixes storage role mapping to
 * {@code participant}, and executes real RESP backup CronJobs / on-demand Jobs.</p>
 */
public final class K8sActionApplier implements ActionApplier {

    public static final String FINALIZER = "tieringkv.io/finalizer";
    public static final String BACKUP_REQUEST_ANNOTATION =
            "tieringkv.io/backup-request-id";
    public static final String CONFIG_HASH_ANNOTATION =
            "tieringkv.io/config-hash";

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
            case TRIGGER_BACKUP -> reconcileBackups(resource);
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
            observed.setLastBackupJob(current.getLastBackupJob());
            observed.setLastBackupStatus(current.getLastBackupStatus());
            observed.setLastBackupTime(current.getLastBackupTime());
            observed.setMessage(current.getMessage());
        }
        observeBackupJob(resource, observed);
        return observed;
    }

    private void ensureCluster(K8sTieringKVCluster resource) {
        ConfigMap configMap = configMap(resource);
        String configHash = configHash(configMap.getData());
        upsertConfigMap(configMap);
        upsertStatefulSet(statefulSet(resource, true, configHash));
        upsertStatefulSet(statefulSet(resource, false, configHash));
        upsertService(service(resource, true, true));
        upsertService(service(resource, false, true));
        upsertService(service(resource, false, false));
        upsertDeployment(deployment(resource, configHash));
        reconcileBackups(resource);
    }

    private void reconcileBackups(K8sTieringKVCluster resource) {
        if (hasBackupSchedule(resource)) {
            upsertBackupCronJob(resource);
        } else {
            deleteBackupCronJob(resource);
        }
        String requestId = backupRequestId(resource);
        if (requestId != null) {
            ensureBackupJob(resource, requestId);
        }
    }

    private void upsertConfigMap(ConfigMap desired) {
        String namespace = desired.getMetadata().getNamespace();
        String name = desired.getMetadata().getName();
        retry(() -> {
            ConfigMap existing = client.configMaps().inNamespace(namespace)
                    .withName(name).get();
            if (existing == null) {
                client.configMaps().inNamespace(namespace).resource(desired)
                        .create();
                return;
            }
            client.configMaps().inNamespace(namespace).withName(name)
                    .edit(cm -> {
                        cm.setData(desired.getData());
                        cm.getMetadata().setLabels(
                                desired.getMetadata().getLabels());
                        if (desired.getMetadata().getOwnerReferences() != null) {
                            cm.getMetadata().setOwnerReferences(
                                    desired.getMetadata().getOwnerReferences());
                        }
                        return cm;
                    });
        });
    }

    /**
     * 安全更新 StatefulSet：首次创建写入完整 volumeClaimTemplates，
     * 已存在时仅 Patch K8s 允许修改的 replicas/template/metadata，避免不可变字段 422。
     */
    private void upsertStatefulSet(StatefulSet desired) {
        String namespace = desired.getMetadata().getNamespace();
        String name = desired.getMetadata().getName();
        retry(() -> {
            StatefulSet existing = client.apps().statefulSets()
                    .inNamespace(namespace).withName(name).get();
            if (existing == null) {
                client.apps().statefulSets().inNamespace(namespace)
                        .resource(desired).create();
                return;
            }
            client.apps().statefulSets().inNamespace(namespace).withName(name)
                    .edit(sts -> {
                        sts.getSpec().setReplicas(
                                desired.getSpec().getReplicas());
                        sts.getSpec().setTemplate(
                                desired.getSpec().getTemplate());
                        sts.getMetadata().setLabels(
                                desired.getMetadata().getLabels());
                        if (desired.getMetadata().getOwnerReferences() != null) {
                            sts.getMetadata().setOwnerReferences(
                                    desired.getMetadata().getOwnerReferences());
                        }
                        return sts;
                    });
        });
    }

    private void upsertDeployment(Deployment desired) {
        String namespace = desired.getMetadata().getNamespace();
        String name = desired.getMetadata().getName();
        retry(() -> {
            Deployment existing = client.apps().deployments()
                    .inNamespace(namespace).withName(name).get();
            if (existing == null) {
                client.apps().deployments().inNamespace(namespace)
                        .resource(desired).create();
                return;
            }
            client.apps().deployments().inNamespace(namespace).withName(name)
                    .edit(dep -> {
                        dep.getSpec().setReplicas(
                                desired.getSpec().getReplicas());
                        dep.getSpec().setTemplate(
                                desired.getSpec().getTemplate());
                        dep.getMetadata().setLabels(
                                desired.getMetadata().getLabels());
                        if (desired.getMetadata().getOwnerReferences() != null) {
                            dep.getMetadata().setOwnerReferences(
                                    desired.getMetadata().getOwnerReferences());
                        }
                        return dep;
                    });
        });
    }

    /** 安全更新 Service：保留集群已分配的不可变 clusterIP。 */
    private void upsertService(Service desired) {
        String namespace = desired.getMetadata().getNamespace();
        String name = desired.getMetadata().getName();
        retry(() -> {
            Service existing = client.services().inNamespace(namespace)
                    .withName(name).get();
            if (existing == null) {
                client.services().inNamespace(namespace).resource(desired)
                        .create();
                return;
            }
            client.services().inNamespace(namespace).withName(name)
                    .edit(svc -> {
                        svc.getSpec().setSelector(
                                desired.getSpec().getSelector());
                        svc.getSpec().setPorts(desired.getSpec().getPorts());
                        svc.getMetadata().setLabels(
                                desired.getMetadata().getLabels());
                        if (desired.getMetadata().getOwnerReferences() != null) {
                            svc.getMetadata().setOwnerReferences(
                                    desired.getMetadata().getOwnerReferences());
                        }
                        return svc;
                    });
        });
    }

    private void upsertBackupCronJob(K8sTieringKVCluster resource) {
        CronJob desired = backupCronJob(resource);
        String namespace = namespace(resource);
        String name = desired.getMetadata().getName();
        retry(() -> {
            CronJob existing = client.batch().v1().cronjobs()
                    .inNamespace(namespace).withName(name).get();
            if (existing == null) {
                client.batch().v1().cronjobs().inNamespace(namespace)
                        .resource(desired).create();
                return;
            }
            client.batch().v1().cronjobs().inNamespace(namespace).withName(name)
                    .edit(cj -> {
                        cj.setSpec(desired.getSpec());
                        cj.getMetadata().setLabels(
                                desired.getMetadata().getLabels());
                        if (desired.getMetadata().getOwnerReferences() != null) {
                            cj.getMetadata().setOwnerReferences(
                                    desired.getMetadata().getOwnerReferences());
                        }
                        return cj;
                    });
        });
    }

    private void ensureBackupJob(K8sTieringKVCluster resource, String requestId) {
        String namespace = namespace(resource);
        String jobName = backupJobName(resource, requestId);
        retry(() -> {
            Job existing = client.batch().v1().jobs().inNamespace(namespace)
                    .withName(jobName).get();
            if (existing != null) {
                return;
            }
            client.batch().v1().jobs().inNamespace(namespace)
                    .resource(backupJob(resource, requestId, jobName))
                    .create();
        });
    }

    private void observeBackupJob(K8sTieringKVCluster resource,
                                  K8sTieringKVClusterStatus observed) {
        String requestId = backupRequestId(resource);
        String jobName = requestId != null
                ? backupJobName(resource, requestId)
                : observed.getLastBackupJob();
        if (jobName == null || jobName.isBlank()) {
            return;
        }
        Job job = client.batch().v1().jobs().inNamespace(namespace(resource))
                .withName(jobName).get();
        if (job == null) {
            return;
        }
        observed.setLastBackupJob(jobName);
        JobStatus status = job.getStatus();
        if (status == null) {
            observed.setLastBackupStatus("RUNNING");
            return;
        }
        if (status.getSucceeded() != null && status.getSucceeded() > 0) {
            observed.setLastBackupStatus("SUCCEEDED");
            if (status.getCompletionTime() != null) {
                observed.setLastBackupTime(status.getCompletionTime());
            }
        } else if (status.getFailed() != null && status.getFailed() > 0) {
            observed.setLastBackupStatus("FAILED");
        } else {
            observed.setLastBackupStatus("RUNNING");
        }
    }

    private void deleteCluster(K8sTieringKVCluster resource) {
        String namespace = namespace(resource);
        deleteIfPresent(() -> client.batch().v1().cronjobs()
                .inNamespace(namespace)
                .withName(backupName(resource)).delete());
        deleteIfPresent(() -> client.batch().v1().jobs()
                .inNamespace(namespace)
                .withLabel("tieringkv.io/cluster", clusterName(resource))
                .delete());
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

    private void deleteBackupCronJob(K8sTieringKVCluster resource) {
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
                                    boolean metadataRole,
                                    String configHash) {
        String name = metadataRole ? metadataName(resource)
                : storageName(resource);
        String tier = metadataRole ? "metadata" : "storage";
        int replicas = metadataRole
                ? resource.getSpec().getMetadataReplicas()
                : resource.getSpec().getStorageReplicas();
        int rpcPort = metadataRole ? METADATA_RPC_PORT : STORAGE_RPC_PORT;
        Map<String, String> labels = labels(resource, tier);
        String storageSize = metadataRole
                ? resource.getSpec().effectiveMetadataStorageSize()
                : resource.getSpec().effectiveStorageDataSize();
        return new StatefulSetBuilder()
                .withApiVersion("apps/v1")
                .withKind("StatefulSet")
                .withMetadata(metadata(name, resource, labels))
                .withSpec(new StatefulSetSpecBuilder()
                        .withServiceName(name)
                        .withReplicas(replicas)
                        .withSelector(selector(labels))
                        .withTemplate(podTemplate(resource, tier,
                                rpcPort, false, true, configHash))
                        .withVolumeClaimTemplates(dataClaim(storageSize,
                                resource.getSpec().getStorageClassName()))
                        .build())
                .build();
    }

    private Deployment deployment(K8sTieringKVCluster resource,
                                  String configHash) {
        Map<String, String> labels = labels(resource, "gateway");
        return new DeploymentBuilder()
                .withApiVersion("apps/v1")
                .withKind("Deployment")
                .withMetadata(metadata(gatewayName(resource), resource,
                        labels))
                .withSpec(new DeploymentSpecBuilder()
                        .withReplicas(
                                resource.getSpec().effectiveGatewayReplicas())
                        .withSelector(selector(labels))
                        .withTemplate(podTemplate(resource, "gateway",
                                GATEWAY_RPC_PORT, true, false, configHash))
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

    private CronJob backupCronJob(K8sTieringKVCluster resource) {
        Map<String, String> labels = labels(resource, "backup");
        PodTemplateSpec pod = backupPodTemplate(resource, labels, "scheduled");
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
                        .withSuspend(false)
                        .withJobTemplate(jobTemplate.build())
                        .build())
                .build();
    }

    private Job backupJob(K8sTieringKVCluster resource,
                          String requestId,
                          String jobName) {
        Map<String, String> labels = labels(resource, "backup");
        PodTemplateSpec pod = backupPodTemplate(resource, labels, requestId);
        return new JobBuilder()
                .withApiVersion("batch/v1")
                .withKind("Job")
                .withMetadata(metadata(jobName, resource, labels))
                .withSpec(new JobSpecBuilder()
                        .withBackoffLimit(2)
                        .withTtlSecondsAfterFinished(86400)
                        .withTemplate(pod)
                        .build())
                .build();
    }

    private PodTemplateSpec backupPodTemplate(K8sTieringKVCluster resource,
                                              Map<String, String> labels,
                                              String requestId) {
        Container backupContainer = new ContainerBuilder()
                .withName("backup")
                .withImage(resource.getSpec().getImage())
                .withImagePullPolicy("IfNotPresent")
                .withCommand("/bin/bash", "-euo", "pipefail", "-c")
                .withArgs(backupScript())
                .withEnv(
                        new EnvVarBuilder().withName("CLUSTER_NAME")
                                .withValue(clusterName(resource)).build(),
                        new EnvVarBuilder().withName("GATEWAY_HOST")
                                .withValue(gatewayName(resource)).build(),
                        new EnvVarBuilder().withName("GATEWAY_PORT")
                                .withValue(String.valueOf(REDIS_PORT)).build(),
                        new EnvVarBuilder().withName("BACKUP_REQUEST_ID")
                                .withValue(requestId).build())
                .build();
        return new PodTemplateSpecBuilder()
                .withMetadata(new ObjectMetaBuilder().withLabels(labels).build())
                .withSpec(new PodSpecBuilder()
                        .withRestartPolicy("OnFailure")
                        .withContainers(backupContainer)
                        .build())
                .build();
    }

    private static String backupScript() {
        return "exec 3<>/dev/tcp/\"$GATEWAY_HOST\"/\"$GATEWAY_PORT\"\n"
                + "printf '*1\\r\\n$4\\r\\nPING\\r\\n' >&3\n"
                + "IFS= read -r -t 5 pong <&3\n"
                + "pong=${pong%$'\\r'}\n"
                + "if [ \"$pong\" != \"+PONG\" ]; then\n"
                + "  echo \"[backup] unexpected PING reply: $pong\" >&2\n"
                + "  exit 1\n"
                + "fi\n"
                + "printf '*1\\r\\n$6\\r\\nDBSIZE\\r\\n' >&3\n"
                + "IFS= read -r -t 5 dbsize <&3\n"
                + "dbsize=${dbsize%$'\\r'}\n"
                + "printf '*1\\r\\n$6\\r\\nBGSAVE\\r\\n' >&3\n"
                + "IFS= read -r -t 5 bgsave <&3 || bgsave=''\n"
                + "bgsave=${bgsave%$'\\r'}\n"
                + "exec 3>&-\n"
                + "echo \"[backup] cluster=$CLUSTER_NAME request=$BACKUP_REQUEST_ID "
                + "ping=$pong dbsize=$dbsize bgsave=$bgsave\"\n";
    }

    private PodTemplateSpec podTemplate(K8sTieringKVCluster resource,
                                        String role,
                                        int rpcPort,
                                        boolean gateway,
                                        boolean withData,
                                        String configHash) {
        Map<String, String> labels = labels(resource, role);
        String runtimeRole = gateway ? "gateway"
                : ("storage".equals(role) ? "participant" : role);
        String portName = gateway ? "redis" : "raft-rpc";
        int containerPort = gateway ? REDIS_PORT : rpcPort;
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
                .withValue(runtimeRole).build());
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
                        .withName(portName)
                        .withContainerPort(containerPort)
                        .build())
                .withReadinessProbe(tcpProbe(portName, 5, 5))
                .withLivenessProbe(tcpProbe(portName, 15, 10))
                .withVolumeMounts(mounts)
                .build();
        List<Volume> volumes = List.of(new VolumeBuilder()
                .withName(CONFIG_VOLUME)
                .withConfigMap(new ConfigMapVolumeSourceBuilder()
                        .withName(configMapName(resource)).build())
                .build());
        return new PodTemplateSpecBuilder()
                .withMetadata(new ObjectMetaBuilder()
                        .withLabels(labels)
                        .addToAnnotations(CONFIG_HASH_ANNOTATION, configHash)
                        .build())
                .withSpec(new PodSpecBuilder()
                        .withTerminationGracePeriodSeconds(gateway ? 30L : 60L)
                        .withContainers(container)
                        .withVolumes(volumes)
                        .build())
                .build();
    }

    private static Probe tcpProbe(String portName,
                                  int initialDelaySeconds,
                                  int periodSeconds) {
        return new ProbeBuilder()
                .withTcpSocket(new TCPSocketActionBuilder()
                        .withPort(new IntOrString(portName))
                        .build())
                .withInitialDelaySeconds(initialDelaySeconds)
                .withPeriodSeconds(periodSeconds)
                .build();
    }

    private PersistentVolumeClaim dataClaim(String size,
                                            String storageClassName) {
        PersistentVolumeClaimSpecBuilder spec =
                new PersistentVolumeClaimSpecBuilder()
                        .withAccessModes("ReadWriteOnce")
                        .withResources(new VolumeResourceRequirementsBuilder()
                                .addToRequests("storage",
                                        new io.fabric8.kubernetes.api.model.Quantity(
                                                size))
                                .build());
        if (storageClassName != null && !storageClassName.isBlank()) {
            spec.withStorageClassName(storageClassName.trim());
        }
        return new PersistentVolumeClaimBuilder()
                .withApiVersion("v1")
                .withKind("PersistentVolumeClaim")
                .withMetadata(new ObjectMetaBuilder().withName(DATA_VOLUME)
                        .build())
                .withSpec(spec.build())
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

    private List<String> effectiveRegionIds(K8sTieringKVCluster resource) {
        List<String> declared = resource.getSpec().getRegionIds();
        int storageReplicas = Math.max(1, resource.getSpec().getStorageReplicas());
        List<String> result = new ArrayList<>();
        Set<String> used = new HashSet<>();
        if (declared != null) {
            for (String id : declared) {
                if (id != null && !id.isBlank() && used.add(id.trim())) {
                    result.add(id.trim());
                }
            }
        }
        int seq = result.size() + 1;
        while (result.size() < storageReplicas) {
            String candidate = "r" + seq++;
            if (used.add(candidate)) {
                result.add(candidate);
            }
        }
        return result;
    }

    private String regionsConfig(K8sTieringKVCluster resource) {
        StringBuilder regions = new StringBuilder();
        List<String> regionIds = effectiveRegionIds(resource);
        for (int i = 0; i < regionIds.size(); i++) {
            regions.append(storageName(resource)).append('-').append(i)
                    .append(' ').append(regionIds.get(i)).append('\n');
        }
        return regions.toString();
    }

    private String regionEndpoints(K8sTieringKVCluster resource) {
        StringJoiner endpoints = new StringJoiner(",");
        List<String> regionIds = effectiveRegionIds(resource);
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
                + "    exec java -Xmx\"${JVM_XMX:-256m}\" -cp /app/tiering-kv.jar "
                + "io.tieringkv.runtime.TxnRuntimeMain "
                + "--role metadata --node-id \"$NODE_ID\" "
                + "--rpc-port \"$RPC_PORT\" --data-dir \"$DATA_DIR\"\n"
                + "    ;;\n"
                + "  participant|storage)\n"
                + "    REGION_ID=$(awk -v name=\"$NODE_ID\" '$1 == name {print $2}' "
                + "/etc/tiering-kv/regions.conf)\n"
                + "    if [ -z \"$REGION_ID\" ]; then REGION_ID=\"r${NODE_ID##*-}\"; fi\n"
                + "    exec java -Xmx\"${JVM_XMX:-512m}\" -cp /app/tiering-kv.jar "
                + "io.tieringkv.runtime.TxnRuntimeMain "
                + "--role participant --node-id \"$NODE_ID\" "
                + "--region-id \"$REGION_ID\" --rpc-port \"$RPC_PORT\" "
                + "--data-dir \"$DATA_DIR\"\n"
                + "    ;;\n"
                + "  gateway)\n"
                + "    exec java -Xmx\"${JVM_XMX:-256m}\" -cp /app/tiering-kv.jar "
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

    private static String configHash(Map<String, String> data) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            data.forEach((k, v) -> {
                md.update(k.getBytes(StandardCharsets.UTF_8));
                md.update((byte) '=');
                md.update((v == null ? "" : v).getBytes(StandardCharsets.UTF_8));
                md.update((byte) '\n');
            });
            return HexFormat.of().formatHex(md.digest()).substring(0, 12);
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("SHA-256 unavailable", ex);
        }
    }

    static String backupJobName(K8sTieringKVCluster resource,
                                String requestId) {
        String sanitized = requestId.toLowerCase(Locale.ROOT)
                .replaceAll("[^a-z0-9-]+", "-")
                .replaceAll("^-+|-+$", "");
        if (sanitized.isEmpty()) {
            sanitized = "manual";
        }
        String suffix = BACKUP_SUFFIX + "-" + sanitized;
        if (suffix.length() > 30) {
            suffix = suffix.substring(0, 30).replaceAll("-+$", "");
        }
        return childName(resource, suffix);
    }

    private static String backupRequestId(K8sTieringKVCluster resource) {
        if (resource.getMetadata() == null
                || resource.getMetadata().getAnnotations() == null) {
            return null;
        }
        String raw = resource.getMetadata().getAnnotations()
                .get(BACKUP_REQUEST_ANNOTATION);
        return raw == null || raw.isBlank() ? null : raw.trim();
    }

    private static int ready(StatefulSet statefulSet) {
        if (statefulSet == null || statefulSet.getStatus() == null
                || statefulSet.getStatus().getReadyReplicas() == null) {
            return 0;
        }
        int ready = statefulSet.getStatus().getReadyReplicas();
        Integer updated = statefulSet.getStatus().getUpdatedReplicas();
        String currentRev = statefulSet.getStatus().getCurrentRevision();
        String updateRev = statefulSet.getStatus().getUpdateRevision();
        if (updated != null && currentRev != null && updateRev != null
                && !currentRev.equals(updateRev)) {
            return Math.min(ready, updated);
        }
        return ready;
    }

    private static int ready(Deployment deployment) {
        if (deployment == null || deployment.getStatus() == null
                || deployment.getStatus().getReadyReplicas() == null) {
            return 0;
        }
        int ready = deployment.getStatus().getReadyReplicas();
        Integer updated = deployment.getStatus().getUpdatedReplicas();
        Integer replicas = deployment.getSpec() == null
                ? null : deployment.getSpec().getReplicas();
        if (updated != null && replicas != null && updated < replicas) {
            return Math.min(ready, updated);
        }
        return ready;
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
        int maxClusterLength = Math.max(1, 63 - suffix.length() - 1);
        if (cluster.length() > maxClusterLength) {
            cluster = cluster.substring(0, maxClusterLength)
                    .replaceAll("-+$", "");
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
