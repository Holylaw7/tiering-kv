package io.tieringkv.saas.operations;

import java.util.ArrayList;
import java.util.List;

/** 商业化告警（ADR-0155）：MRR 下跌 / 流失率 / 试用转化阈值。 */
public final class CommercialAlert {

    public static final double DEFAULT_CHURN_THRESHOLD = 0.05;
    public static final double DEFAULT_CONVERSION_THRESHOLD = 0.30;
    public static final double DEFAULT_MRR_DROP_THRESHOLD = 0.10;

    /** 告警项：类型 + 消息。 */
    public record Alert(String type, String message) {
    }

    public List<Alert> evaluate(double churnRate,
                                double conversionRate,
                                double mrrNow, double mrrBefore,
                                double churnThreshold,
                                double conversionThreshold,
                                double mrrDropThreshold) {
        List<Alert> alerts = new ArrayList<>();
        if (churnRate > churnThreshold) {
            alerts.add(new Alert("CHURN",
                    "churn rate " + churnRate
                            + " exceeds threshold "
                            + churnThreshold));
        }
        if (conversionRate < conversionThreshold) {
            alerts.add(new Alert("CONVERSION",
                    "trial conversion " + conversionRate
                            + " below threshold "
                            + conversionThreshold));
        }
        if (mrrBefore > 0
                && mrrNow < mrrBefore * (1 - mrrDropThreshold)) {
            alerts.add(new Alert("MRR_DROP",
                    "mrr " + mrrNow + " dropped from "
                            + mrrBefore));
        }
        return alerts;
    }

    /** 便捷重载：默认阈值 + 组件快照。 */
    public List<Alert> evaluate(ChurnDetector churn,
                                TrialConversionTracker conversion,
                                double mrrNow, double mrrBefore) {
        return evaluate(churn.churnRate(),
                conversion.conversionRate(), mrrNow, mrrBefore,
                DEFAULT_CHURN_THRESHOLD,
                DEFAULT_CONVERSION_THRESHOLD,
                DEFAULT_MRR_DROP_THRESHOLD);
    }
}
