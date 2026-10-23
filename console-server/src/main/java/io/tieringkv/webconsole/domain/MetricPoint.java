package io.tieringkv.webconsole.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import java.time.LocalDateTime;

/** 时序指标采样点（由采集器周期性写入）。 */
@Entity
@Table(name = "metric_point")
public class MetricPoint {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "instance_id", nullable = false)
    private Long instanceId;

    @Column(name = "collected_at", nullable = false)
    private LocalDateTime collectedAt;

    private Double qps;

    @Column(name = "ops_per_sec")
    private Double opsPerSec;

    @Column(name = "avg_latency_ms")
    private Double avgLatencyMs;

    private Integer connections;

    @Column(name = "active_requests")
    private Integer activeRequests;

    private Long errors;

    @Column(name = "used_memory")
    private Long usedMemory;

    @Column(name = "max_memory")
    private Long maxMemory;

    @Column(name = "total_keys")
    private Long totalKeys;

    @Column(name = "uptime_seconds")
    private Long uptimeSeconds;

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

    public LocalDateTime getCollectedAt() {
        return collectedAt;
    }

    public void setCollectedAt(LocalDateTime collectedAt) {
        this.collectedAt = collectedAt;
    }

    public Double getQps() {
        return qps;
    }

    public void setQps(Double qps) {
        this.qps = qps;
    }

    public Double getOpsPerSec() {
        return opsPerSec;
    }

    public void setOpsPerSec(Double opsPerSec) {
        this.opsPerSec = opsPerSec;
    }

    public Double getAvgLatencyMs() {
        return avgLatencyMs;
    }

    public void setAvgLatencyMs(Double avgLatencyMs) {
        this.avgLatencyMs = avgLatencyMs;
    }

    public Integer getConnections() {
        return connections;
    }

    public void setConnections(Integer connections) {
        this.connections = connections;
    }

    public Integer getActiveRequests() {
        return activeRequests;
    }

    public void setActiveRequests(Integer activeRequests) {
        this.activeRequests = activeRequests;
    }

    public Long getErrors() {
        return errors;
    }

    public void setErrors(Long errors) {
        this.errors = errors;
    }

    public Long getUsedMemory() {
        return usedMemory;
    }

    public void setUsedMemory(Long usedMemory) {
        this.usedMemory = usedMemory;
    }

    public Long getMaxMemory() {
        return maxMemory;
    }

    public void setMaxMemory(Long maxMemory) {
        this.maxMemory = maxMemory;
    }

    public Long getTotalKeys() {
        return totalKeys;
    }

    public void setTotalKeys(Long totalKeys) {
        this.totalKeys = totalKeys;
    }

    public Long getUptimeSeconds() {
        return uptimeSeconds;
    }

    public void setUptimeSeconds(Long uptimeSeconds) {
        this.uptimeSeconds = uptimeSeconds;
    }
}
