package io.tieringkv.webconsole.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 多副本协调租约锁记录（M3）：承载定时任务选主与跨副本备份/恢复互斥。 */
@Entity
@Table(name = "console_lease")
public class ConsoleLease {

    @Id
    @Column(name = "lease_key", nullable = false, length = 128)
    private String leaseKey;

    @Column(name = "owner_id", nullable = false, length = 64)
    private String ownerId;

    @Column(name = "lease_until", nullable = false)
    private LocalDateTime leaseUntil;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getLeaseKey() {
        return leaseKey;
    }

    public void setLeaseKey(String leaseKey) {
        this.leaseKey = leaseKey;
    }

    public String getOwnerId() {
        return ownerId;
    }

    public void setOwnerId(String ownerId) {
        this.ownerId = ownerId;
    }

    public LocalDateTime getLeaseUntil() {
        return leaseUntil;
    }

    public void setLeaseUntil(LocalDateTime leaseUntil) {
        this.leaseUntil = leaseUntil;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
