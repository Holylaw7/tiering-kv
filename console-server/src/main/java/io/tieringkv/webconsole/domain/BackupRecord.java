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

    /** 导出进行中已处理键数（O1-B），完成后与 keyCount 对齐。 */
    @Column(name = "processed_keys", nullable = false)
    private long processedKeys;

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

    /** 归档字节流 SHA-256 十六进制摘要（v6.4.0 D1）。 */
    @Column(name = "sha256", length = 64)
    private String sha256;

    /** 归档完整性校验状态（v6.4.0 D1）。 */
    @Enumerated(EnumType.STRING)
    @Column(name = "verify_status", nullable = false, length = 16)
    private BackupVerifyStatus verifyStatus = BackupVerifyStatus.UNVERIFIED;

    /** 最近一次完整性校验时间（v6.4.0 D1）。 */
    @Column(name = "verified_at")
    private LocalDateTime verifiedAt;

    /** 恢复前自动生成的安全保护快照 ID（v6.4.0 D1）。 */
    @Column(name = "pre_restore_backup_id")
    private Long preRestoreBackupId;

    public String getSha256() {
        return sha256;
    }

    public void setSha256(String sha256) {
        this.sha256 = sha256;
    }

    public BackupVerifyStatus getVerifyStatus() {
        return verifyStatus;
    }

    public void setVerifyStatus(BackupVerifyStatus verifyStatus) {
        this.verifyStatus = verifyStatus;
    }

    public LocalDateTime getVerifiedAt() {
        return verifiedAt;
    }

    public void setVerifiedAt(LocalDateTime verifiedAt) {
        this.verifiedAt = verifiedAt;
    }

    public Long getPreRestoreBackupId() {
        return preRestoreBackupId;
    }

    public void setPreRestoreBackupId(Long preRestoreBackupId) {
        this.preRestoreBackupId = preRestoreBackupId;
    }

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

    public long getProcessedKeys() {
        return processedKeys;
    }

    public void setProcessedKeys(long processedKeys) {
        this.processedKeys = processedKeys;
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