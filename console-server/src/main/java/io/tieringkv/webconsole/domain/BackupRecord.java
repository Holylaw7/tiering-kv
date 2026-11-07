package io.tieringkv.webconsole.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 逻辑备份记录。 */
@Entity
@Table(name = "backup_record")
public class BackupRecord {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "file_name", nullable = false, length = 128)
    private String fileName;

    @Column(name = "size_bytes", nullable = false)
    private long sizeBytes;

    @Column(name = "key_count", nullable = false)
    private long keyCount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private BackupStatus status = BackupStatus.RUNNING;

    @Column(length = 255)
    private String message;

    @Column(name = "created_at", nullable = false)
    private LocalDateTime createdAt;

    @Column(name = "finished_at")
    private LocalDateTime finishedAt;

    /** 恢复状态与备份状态分离（ADR-0366）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "restore_status", nullable = false, length = 16)
    private RestoreStatus restoreStatus = RestoreStatus.NONE;

    @Column(name = "restore_started_at")
    private LocalDateTime restoreStartedAt;

    @Column(name = "restore_finished_at")
    private LocalDateTime restoreFinishedAt;

    /** 已回放键数；失败时为部分值，用于向用户说明"恢复了多少"。 */
    @Column(name = "restored_keys", nullable = false)
    private long restoredKeys;

    @Column(name = "restore_message", length = 255)
    private String restoreMessage;

    /** 清理认领状态：NULL 正常；RETIRING 已被某个实例认领，正在删除（ADR-0367）。 */
    @Column(name = "delete_state", length = 16)
    private String deleteState;

    @Column(name = "delete_claimed_at")
    private LocalDateTime deleteClaimedAt;

    public String getDeleteState() {
        return deleteState;
    }

    public void setDeleteState(String deleteState) {
        this.deleteState = deleteState;
    }

    public LocalDateTime getDeleteClaimedAt() {
        return deleteClaimedAt;
    }

    public void setDeleteClaimedAt(LocalDateTime deleteClaimedAt) {
        this.deleteClaimedAt = deleteClaimedAt;
    }

    public RestoreStatus getRestoreStatus() {
        return restoreStatus;
    }

    public void setRestoreStatus(RestoreStatus restoreStatus) {
        this.restoreStatus = restoreStatus;
    }

    public LocalDateTime getRestoreStartedAt() {
        return restoreStartedAt;
    }

    public void setRestoreStartedAt(LocalDateTime restoreStartedAt) {
        this.restoreStartedAt = restoreStartedAt;
    }

    public LocalDateTime getRestoreFinishedAt() {
        return restoreFinishedAt;
    }

    public void setRestoreFinishedAt(LocalDateTime restoreFinishedAt) {
        this.restoreFinishedAt = restoreFinishedAt;
    }

    public long getRestoredKeys() {
        return restoredKeys;
    }

    public void setRestoredKeys(long restoredKeys) {
        this.restoredKeys = restoredKeys;
    }

    public String getRestoreMessage() {
        return restoreMessage;
    }

    public void setRestoreMessage(String restoreMessage) {
        this.restoreMessage = restoreMessage;
    }

    public Long getId() {
        return id;
    }

    public void setId(Long id) {
        this.id = id;
    }

    public Long getInstanceId() {
        return instanceId;
    }

    public void setInstanceId(Long instanceId) {
        this.instanceId = instanceId;
    }

    public String getFileName() {
        return fileName;
    }

    public void setFileName(String fileName) {
        this.fileName = fileName;
    }

    public long getSizeBytes() {
        return sizeBytes;
    }

    public void setSizeBytes(long sizeBytes) {
        this.sizeBytes = sizeBytes;
    }

    public long getKeyCount() {
        return keyCount;
    }

    public void setKeyCount(long keyCount) {
        this.keyCount = keyCount;
    }

    public BackupStatus getStatus() {
        return status;
    }

    public void setStatus(BackupStatus status) {
        this.status = status;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public LocalDateTime getCreatedAt() {
        return createdAt;
    }

    public void setCreatedAt(LocalDateTime createdAt) {
        this.createdAt = createdAt;
    }

    public LocalDateTime getFinishedAt() {
        return finishedAt;
    }

    public void setFinishedAt(LocalDateTime finishedAt) {
        this.finishedAt = finishedAt;
    }
}