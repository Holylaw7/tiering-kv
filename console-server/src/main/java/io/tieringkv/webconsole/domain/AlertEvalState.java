package io.tieringkv.webconsole.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/**
 * 告警求值持续时长与 Webhook 通知静默/防重发持久化状态（Wave 2A H2）。
 *
 * <p>将原单机 JVM 内存中的 {@code satisfiedSince} 与 {@code lastSentByKey} 下沉至 MySQL，
 * 使多副本 HA 重启、主备切换与并发调度下均保持强一致的告警计时与通知去重语义。
 */
@Entity
@Table(name = "alert_eval_state")
public class AlertEvalState {

    @Id
    @Column(name = "state_key", nullable = false, length = 128)
    private String stateKey;

    @Column(name = "satisfied_since")
    private LocalDateTime satisfiedSince;

    @Column(name = "last_notified_at")
    private LocalDateTime lastNotifiedAt;

    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public String getStateKey() {
        return stateKey;
    }

    public void setStateKey(String stateKey) {
        this.stateKey = stateKey;
    }

    public LocalDateTime getSatisfiedSince() {
        return satisfiedSince;
    }

    public void setSatisfiedSince(LocalDateTime satisfiedSince) {
        this.satisfiedSince = satisfiedSince;
    }

    public LocalDateTime getLastNotifiedAt() {
        return lastNotifiedAt;
    }

    public void setLastNotifiedAt(LocalDateTime lastNotifiedAt) {
        this.lastNotifiedAt = lastNotifiedAt;
    }

    public LocalDateTime getUpdatedAt() {
        return updatedAt;
    }

    public void setUpdatedAt(LocalDateTime updatedAt) {
        this.updatedAt = updatedAt;
    }
}
