package io.tieringkv.webconsole.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 容量预留的锁锚点：每个存储路径一行，串行化预留判定（ADR-0369）。 */
@Entity
@Table(name = "backup_capacity")
public class BackupCapacity {

    @Id
    @Column(name = "storage_key", length = 512)
    private String storageKey;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getStorageKey() { return storageKey; }
    public void setStorageKey(String storageKey) { this.storageKey = storageKey; }
    public LocalDateTime getUpdatedAt() { return updatedAt; }
    public void setUpdatedAt(LocalDateTime updatedAt) { this.updatedAt = updatedAt; }
}
