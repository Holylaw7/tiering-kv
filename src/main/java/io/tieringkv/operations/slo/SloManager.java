package io.tieringkv.operations.slo;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/** SLO 管理（ADR-0162）：滚动窗口达成率 + 状态。 */
public final class SloManager {

    /** SLO 定义：指标 + 目标达成率 + 窗口。 */
    public record SloDefinition(String sloId, String metric,
                                double target, int windowSize) {

        public SloDefinition {
            if (sloId == null || sloId.isBlank()) {
                throw new IllegalArgumentException(
                        "sloId required");
            }
            if (metric == null || metric.isBlank()) {
                throw new IllegalArgumentException(
                        "metric required");
            }
            if (target < 0 || target > 1) {
                throw new IllegalArgumentException(
                        "target must be in [0,1]");
            }
            if (windowSize < 1) {
                throw new IllegalArgumentException(
                        "window size must be positive");
            }
        }
    }

    public enum Status {
        COMPLIANT,
        AT_RISK,
        BREACHED
    }

    /** 快照：达成率 + 状态。 */
    public record SloSnapshot(String sloId, double compliance,
                              Status status, int windowSize) {
    }

    private static final double AT_RISK_BAND = 0.10;

    private final Map<String, SloDefinition> definitions =
            new ConcurrentHashMap<>();
    private final Map<String, Deque<Boolean>> windows =
            new ConcurrentHashMap<>();

    public void define(SloDefinition definition) {
        if (definition == null) {
            throw new IllegalArgumentException(
                    "definition required");
        }
        if (definitions.putIfAbsent(definition.sloId(),
                definition) != null) {
            throw new IllegalArgumentException(
                    "slo already defined: " + definition.sloId());
        }
        windows.put(definition.sloId(), new ArrayDeque<>());
    }

    /** 记录窗口样本（true = 达成）。 */
    public void record(String sloId, boolean success) {
        SloDefinition definition = require(sloId);
        Deque<Boolean> window = windows.computeIfAbsent(sloId,
                ignored -> new ArrayDeque<>());
        synchronized (window) {
            window.addLast(success);
            while (window.size() > definition.windowSize()) {
                window.removeFirst();
            }
        }
    }

    /** 达成率：窗口内成功 / 窗口容量（样本不足按已记录算）。 */
    public double compliance(String sloId) {
        SloDefinition definition = require(sloId);
        Deque<Boolean> window = windows.get(sloId);
        if (window == null || window.isEmpty()) {
            return 1.0;
        }
        synchronized (window) {
            long success = window.stream()
                    .filter(Boolean::booleanValue).count();
            return (double) success / window.size();
        }
    }

    public Status status(String sloId) {
        SloDefinition definition = require(sloId);
        double compliance = compliance(sloId);
        if (compliance >= definition.target()) {
            return Status.COMPLIANT;
        }
        if (compliance >= definition.target() - AT_RISK_BAND) {
            return Status.AT_RISK;
        }
        return Status.BREACHED;
    }

    public SloSnapshot snapshot(String sloId) {
        SloDefinition definition = require(sloId);
        return new SloSnapshot(sloId, compliance(sloId),
                status(sloId), definition.windowSize());
    }

    public List<String> sloIds() {
        return List.copyOf(definitions.keySet());
    }

    public void reset(String sloId) {
        Deque<Boolean> window = windows.get(sloId);
        if (window != null) {
            synchronized (window) {
                window.clear();
            }
        }
    }

    private SloDefinition require(String sloId) {
        SloDefinition definition = definitions.get(sloId);
        if (definition == null) {
            throw new IllegalArgumentException(
                    "unknown slo " + sloId);
        }
        return definition;
    }
}
