package io.tieringkv.operations.slo;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;

/** SLO 告警（ADR-0162）：AT_RISK / BREACHED 告警。 */
public final class SloAlert {

    /** 告警项：SLO + 状态 + 消息。 */
    public record Alert(String sloId, String status,
                        String message) {
    }

    public List<Alert> evaluate(SloManager manager,
                                Collection<String> sloIds) {
        if (manager == null || sloIds == null) {
            throw new IllegalArgumentException(
                    "manager and sloIds required");
        }
        List<Alert> alerts = new ArrayList<>();
        for (String sloId : sloIds) {
            SloManager.SloSnapshot snapshot = manager.snapshot(sloId);
            if (snapshot.status()
                    == SloManager.Status.BREACHED) {
                alerts.add(new Alert(sloId, "BREACHED",
                        "slo " + sloId + " breached at "
                                + snapshot.compliance()));
            } else if (snapshot.status()
                    == SloManager.Status.AT_RISK) {
                alerts.add(new Alert(sloId, "AT_RISK",
                        "slo " + sloId + " at risk: "
                                + snapshot.compliance()));
            }
        }
        return alerts;
    }
}
