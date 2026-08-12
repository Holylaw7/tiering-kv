package io.tieringkv.datamesh;

import io.tieringkv.compliance.DataResidencyPolicy;
import io.tieringkv.datamesh.RemoteMaterializationManager.RemoteSnapshot;

import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 对象存储归档（ADR-0194）：冷层视图 → S3 兼容存储。 */
public final class ObjectStorageArchive {

    /** 对象：视图快照 + 归档时间 + 存储云。 */
    public record ArchivedObject(String objectKey, String cloud,
                                 RemoteSnapshot snapshot,
                                 long archivedAtMillis) {
    }

    private final Map<String, ArchivedObject> objects =
            new ConcurrentHashMap<>();
    private final DataResidencyPolicy policy;
    private final String storageCloud;

    public ObjectStorageArchive(DataResidencyPolicy policy,
                                String storageCloud) {
        this.policy = policy;
        this.storageCloud = storageCloud;
    }

    /** 上传：主权校验（视图驻留 == 存储云驻留）。 */
    public ArchivedObject upload(RemoteSnapshot snapshot,
                                 long archivedAtMillis) {
        if (snapshot == null) {
            throw new IllegalArgumentException(
                    "snapshot required");
        }
        if (!policy.required(snapshot.remoteCloud())
                .equals(policy.required(storageCloud))) {
            throw new SecurityException(
                    "cross-residency archive denied: "
                            + snapshot.remoteCloud() + " -> "
                            + storageCloud);
        }
        ArchivedObject object = new ArchivedObject(
                "obj-" + snapshot.viewId(), storageCloud,
                snapshot, archivedAtMillis);
        objects.put(object.objectKey(), object);
        return object;
    }

    public Optional<ArchivedObject> download(String objectKey) {
        return Optional.ofNullable(objects.get(objectKey));
    }

    public void delete(String objectKey) {
        objects.remove(objectKey);
    }

    public Set<String> objectKeys() {
        return Set.copyOf(objects.keySet());
    }

    public int size() {
        return objects.size();
    }
}
