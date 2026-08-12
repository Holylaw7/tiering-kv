package io.tieringkv.capacity.ai;

import java.util.ArrayList;
import java.util.List;

/**
 * 趋势预测（ADR-0147）：线性/指数拟合 + 置信带；误差可度量。
 */
public final class TrendPredictor {

    /** 历史观测点：x 为周期，y 为指标值。 */
    public record Point(long x, double y) {
    }

    /** 预测结果：中心值 + 置信带。 */
    public record Prediction(double value, double lower, double upper) {
    }

    private final double bandFactor;

    public TrendPredictor() {
        this(1.96);
    }

    public TrendPredictor(double bandFactor) {
        if (bandFactor <= 0) {
            throw new IllegalArgumentException(
                    "band factor must be positive");
        }
        this.bandFactor = bandFactor;
    }

    /** 线性最小二乘预测。 */
    public Prediction linear(List<Point> points, long targetX) {
        requirePoints(points);
        Fit fit = linearFit(points);
        double value = fit.slope() * targetX + fit.intercept();
        if (fit.degenerate()) {
            return new Prediction(value, value, value);
        }
        double residualStd = residualStd(points, fit);
        double band = bandFactor * residualStd;
        return new Prediction(value, value - band, value + band);
    }

    /** 指数预测：log(y) 线性拟合后还原。 */
    public Prediction exponential(List<Point> points, long targetX) {
        requirePoints(points);
        for (Point point : points) {
            if (point.y() <= 0) {
                throw new IllegalArgumentException(
                        "exponential requires positive y");
            }
        }
        List<Point> logPoints = new ArrayList<>();
        for (Point point : points) {
            logPoints.add(new Point(point.x(),
                    Math.log(point.y())));
        }
        Fit logFit = linearFit(logPoints);
        double value = Math.exp(logFit.slope() * targetX
                + logFit.intercept());
        double residualStd = residualStd(logPoints, logFit);
        double band = bandFactor * residualStd;
        Prediction logPrediction = new Prediction(
                Math.log(value),
                Math.log(value) - band,
                Math.log(value) + band);
        double lower = Math.max(0,
                Math.exp(logPrediction.lower()));
        double upper = Math.exp(logPrediction.upper());
        return new Prediction(value, lower, upper);
    }

    /** 自动选择：按残差平方和选择更优模型。 */
    public Prediction auto(List<Point> points, long targetX) {
        requirePoints(points);
        try {
            return linearSse(points) <= exponentialSse(points)
                    ? linear(points, targetX)
                    : exponential(points, targetX);
        } catch (IllegalArgumentException e) {
            return linear(points, targetX);
        }
    }

    /** 线性模型样本内残差平方和：拟合质量度量。 */
    public double linearSse(List<Point> points) {
        requirePoints(points);
        return sse(points, linearFit(points));
    }

    /** 指数模型样本内残差平方和：拟合质量度量。 */
    public double exponentialSse(List<Point> points) {
        requirePoints(points);
        for (Point point : points) {
            if (point.y() <= 0) {
                throw new IllegalArgumentException(
                        "exponential requires positive y");
            }
        }
        List<Point> logPoints = new ArrayList<>();
        for (Point point : points) {
            logPoints.add(new Point(point.x(),
                    Math.log(point.y())));
        }
        Fit logFit = linearFit(logPoints);
        double sum = 0;
        for (Point point : points) {
            double fitted = Math.exp(logFit.slope() * point.x()
                    + logFit.intercept());
            double residual = point.y() - fitted;
            sum += residual * residual;
        }
        return sum;
    }

    private static Fit linearFit(List<Point> points) {
        int n = points.size();
        double sumX = 0;
        double sumY = 0;
        double sumXY = 0;
        double sumXX = 0;
        for (Point point : points) {
            sumX += point.x();
            sumY += point.y();
            sumXY += (double) point.x() * point.y();
            sumXX += (double) point.x() * point.x();
        }
        double denominator = n * sumXX - sumX * sumX;
        if (Math.abs(denominator) < 1e-12) {
            double mean = sumY / n;
            return new Fit(0, mean, true);
        }
        double slope = (n * sumXY - sumX * sumY) / denominator;
        double intercept = (sumY - slope * sumX) / n;
        return new Fit(slope, intercept, false);
    }

    private static double sse(List<Point> points, Fit fit) {
        double sum = 0;
        for (Point point : points) {
            double residual = point.y()
                    - (fit.slope() * point.x() + fit.intercept());
            sum += residual * residual;
        }
        return sum;
    }

    private double residualStd(List<Point> points, Fit fit) {
        if (points.size() < 2) {
            return 0;
        }
        double sum = 0;
        for (Point point : points) {
            double residual = point.y()
                    - (fit.slope() * point.x() + fit.intercept());
            sum += residual * residual;
        }
        return Math.sqrt(sum / (points.size() - 1));
    }

    private record Fit(double slope, double intercept,
                       boolean degenerate) {
    }

    private static void requirePoints(List<Point> points) {
        if (points == null || points.size() < 2) {
            throw new IllegalArgumentException(
                    "at least two points required");
        }
    }
}
