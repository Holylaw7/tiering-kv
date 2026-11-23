package io.tieringkv.observability.cost;

import java.util.List;

/** Spot 中断率预测（ADR-0189）：移动平均 / 指数平滑。 */
public final class SpotRatePredictor {

    /** 移动平均预测（窗口内均值）。 */
    public double movingAverage(List<Double> rates, int window) {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException(
                    "rates required");
        }
        if (window < 1) {
            throw new IllegalArgumentException(
                    "window must be positive");
        }
        int size = Math.min(window, rates.size());
        double sum = 0;
        for (int i = rates.size() - size; i < rates.size(); i++) {
            sum += rates.get(i);
        }
        return sum / size;
    }

    /** 指数平滑预测（最后一个平滑值）。 */
    public double exponentialSmoothing(List<Double> rates,
                                       double alpha) {
        if (rates == null || rates.isEmpty()) {
            throw new IllegalArgumentException(
                    "rates required");
        }
        if (alpha <= 0 || alpha > 1) {
            throw new IllegalArgumentException(
                    "alpha must be in (0,1]");
        }
        double smoothed = rates.get(0);
        for (int i = 1; i < rates.size(); i++) {
            smoothed = alpha * rates.get(i)
                    + (1 - alpha) * smoothed;
        }
        return smoothed;
    }
}
