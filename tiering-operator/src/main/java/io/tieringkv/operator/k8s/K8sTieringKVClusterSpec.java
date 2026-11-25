package io.tieringkv.operator.k8s;

import java.util.ArrayList;
import java.util.List;

/** K8s CRD spec（ADR-0322 M4 增强）：期望集群拓扑。 */
public final class K8sTieringKVClusterSpec {

    private int metadataReplicas;
    private int storageReplicas;
    private Integer gatewayReplicas;
    private List<String> regionIds = new ArrayList<>();
    private String image;
    private String storageClassName;
    private String metadataStorageSize;
    private String storageDataSize;
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

    public Integer getGatewayReplicas() {
        return gatewayReplicas;
    }

    public void setGatewayReplicas(Integer gatewayReplicas) {
        this.gatewayReplicas = gatewayReplicas;
    }

    public int effectiveGatewayReplicas() {
        return gatewayReplicas == null || gatewayReplicas < 1
                ? 1 : gatewayReplicas;
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

    public String getStorageClassName() {
        return storageClassName;
    }

    public void setStorageClassName(String storageClassName) {
        this.storageClassName = storageClassName;
    }

    public String getMetadataStorageSize() {
        return metadataStorageSize;
    }

    public void setMetadataStorageSize(String metadataStorageSize) {
        this.metadataStorageSize = metadataStorageSize;
    }

    public String effectiveMetadataStorageSize() {
        return metadataStorageSize == null || metadataStorageSize.isBlank()
                ? "10Gi" : metadataStorageSize.trim();
    }

    public String getStorageDataSize() {
        return storageDataSize;
    }

    public void setStorageDataSize(String storageDataSize) {
        this.storageDataSize = storageDataSize;
    }

    public String effectiveStorageDataSize() {
        return storageDataSize == null || storageDataSize.isBlank()
                ? "100Gi" : storageDataSize.trim();
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
