package io.tieringkv.capacity.ai;

import io.tieringkv.monitor.CapacityPlanner;

import java.util.List;

/**
 * 自动容量建议（ADR-0147）：趋势预测 → 扩容建议 + 风险等级，
 * 与 CapacityPlanner（Phase 30）联动。
 */
public final class AutoCapacityAdvisor {

    public enum RiskLevel {
        LOW,
        MEDIUM,
        HIGH
    }

    /** 建议：当前/预测指标、节点估算、风险与置信。 */
    public record Advice(String metric, long current, long projected,
                         int nodes, int currentNodes,
                         RiskLevel risk, double confidence) {

        public boolean needsScaleUp() {
            return nodes > currentNodes;
        }
    }

    private final CapacityPlanner planner;
    private final TrendPredictor predictor;

    public AutoCapacityAdvisor(CapacityPlanner planner,
                               TrendPredictor predictor) {
        this.planner = planner;
        this.predictor = predictor;
    }

    /** 线性预测容量建议。 */
    public Advice adviseLinear(String metric,
                               List<TrendPredictor.Point> history,
                               long targetX, int shards, long storageGB,
                               int currentNodes, long storagePerNodeGB,
                               long qpsPerNode) {
        TrendPredictor.Prediction prediction =
                predictor.linear(history, targetX);
        return advise(metric, history, prediction, shards, storageGB,
                currentNodes, storagePerNodeGB, qpsPerNode);
    }

    /** 指数预测容量建议。 */
    public Advice adviseExponential(String metric,
                                    List<TrendPredictor.Point> history,
                                    long targetX, int shards,
                                    long storageGB, int currentNodes,
                                    long storagePerNodeGB,
                                    long qpsPerNode) {
        TrendPredictor.Prediction prediction =
                predictor.exponential(history, targetX);
        return advise(metric, history, prediction, shards, storageGB,
                currentNodes, storagePerNodeGB, qpsPerNode);
    }

    /** 自动选择更优模型的容量建议。 */
    public Advice adviseAuto(String metric,
                             List<TrendPredictor.Point> history,
                             long targetX, int shards, long storageGB,
                             int currentNodes, long storagePerNodeGB,
                             long qpsPerNode) {
        TrendPredictor.Prediction prediction =
                predictor.auto(history, targetX);
        return advise(metric, history, prediction, shards, storageGB,
                currentNodes, storagePerNodeGB, qpsPerNode);
    }

    private Advice advise(String metric,
                          List<TrendPredictor.Point> history,
                          TrendPredictor.Prediction prediction,
                          int shards, long storageGB, int currentNodes,
                          long storagePerNodeGB, long qpsPerNode) {
        long projectedQps = Math.max(0, Math.round(
                prediction.value()));
        CapacityPlanner.CapacityEstimate estimate = planner.estimate(
                shards, storageGB, projectedQps, storagePerNodeGB,
                qpsPerNode);
        int nodes = estimate.nodes();
        RiskLevel risk = risk(nodes, currentNodes, prediction);
        double confidence = confidence(history, prediction);
        long current = history.get(history.size() - 1).y() >= 0
                ? Math.round(history.get(history.size() - 1).y()) : 0;
        return new Advice(metric, current, projectedQps, nodes,
                currentNodes, risk, confidence);
    }

    private static RiskLevel risk(int nodes, int currentNodes,
                                  TrendPredictor.Prediction prediction) {
        double bandWidth = prediction.upper() - prediction.lower();
        double relativeBand = Math.max(1,
                Math.abs(prediction.value())) == 0
                ? bandWidth : bandWidth / Math.max(1,
                Math.abs(prediction.value()));
        if (nodes >= Math.max(1, currentNodes) * 2
                || relativeBand > 1.0) {
            return RiskLevel.HIGH;
        }
        if (nodes > currentNodes || relativeBand > 0.5) {
            return RiskLevel.MEDIUM;
        }
        return RiskLevel.LOW;
    }

    private static double confidence(
            List<TrendPredictor.Point> history,
            TrendPredictor.Prediction prediction) {
        double scale = Math.max(1,
                Math.abs(prediction.value()));
        double relativeError = Math.min(1,
                (prediction.upper() - prediction.lower()) / scale);
        double sampleFactor = Math.min(1,
                history.size() / 10.0);
        return 1.0 - relativeError * (1.0 - sampleFactor * 0.5);
    }
}
