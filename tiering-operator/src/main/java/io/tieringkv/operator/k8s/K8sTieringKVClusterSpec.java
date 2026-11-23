package io.tieringkv.operator.k8s;

import java.util.ArrayList;
import java.util.List;

/** K8s CRD spec（ADR-0322 M4 增强）：期望集群拓扑。 */
public final class K8sTieringKVClusterSpec {

    private int metadataReplicas;
    private int storageReplicas;
    private List<String> regionIds = new ArrayList<>();
    private String image;
    private String backupScheduleCron;
    private long backupRetentionHours;

    public int getMetadataReplicas() {
        return metadataReplicas;
    }

    public void setMetadataReplicas(int metadataReplicas) {
        this.metadataReplicas = metadataReplicas;
    }

    public int getStorageReplicas() {
        return storageReplicas;
    }

    public void setStorageReplicas(int storageReplicas) {
        this.storageReplicas = storageReplicas;
    }

    public List<String> getRegionIds() {
        return regionIds;
    }

    public void setRegionIds(List<String> regionIds) {
        this.regionIds = regionIds == null
                ? new ArrayList<>() : new ArrayList<>(regionIds);
    }

    public String getImage() {
        return image;
    }

    public void setImage(String image) {
        this.image = image;
    }

    public String getBackupScheduleCron() {
        return backupScheduleCron;
    }

    public void setBackupScheduleCron(String backupScheduleCron) {
        this.backupScheduleCron = backupScheduleCron;
    }

    public long getBackupRetentionHours() {
        return backupRetentionHours;
    }

    public void setBackupRetentionHours(long backupRetentionHours) {
        this.backupRetentionHours = backupRetentionHours;
    }
}
