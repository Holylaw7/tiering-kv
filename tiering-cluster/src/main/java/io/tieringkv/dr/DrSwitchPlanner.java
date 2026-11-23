package io.tieringkv.dr;

import java.util.ArrayList;
import java.util.List;

/** 切换计划器（ADR-0115）：计划内切换与故障切换。 */
public final class DrSwitchPlanner {

    public SwitchPlan plannedSwitch(DrTopology topology,
                                    String from, String to) {
        if (topology.role(from) != DrRole.PRIMARY) {
            throw new IllegalArgumentException(from + " is not primary");
        }
        if (topology.role(to) != DrRole.SECONDARY) {
            throw new IllegalArgumentException(to + " is not secondary");
        }
        List<String> actions = new ArrayList<>();
        actions.add("flush-decisions:" + from);
        actions.add("catch-up:" + to);
        actions.add("promote:" + to);
        actions.add("demote:" + from);
        return new SwitchPlan(actions, 0, true);
    }

    public SwitchPlan failover(DrTopology topology, String failedRegion) {
        List<String> actions = new ArrayList<>();
        DrRole failedRole = topology.role(failedRegion);
        if (failedRole != DrRole.PRIMARY
                && failedRole != DrRole.SECONDARY) {
            throw new IllegalArgumentException(
                    "region not eligible for failover: " + failedRegion);
        }
        actions.add("detect:" + failedRegion);
        actions.add("promote-secondary");
        actions.add("redirect-gateway");
        boolean async = topology.modes().getOrDefault(failedRegion,
                io.tieringkv.replication.ReplicationMode.SYNC)
                == io.tieringkv.replication.ReplicationMode.ASYNC;
        long expectedRpo = async ? 5_000 : 0;
        return new SwitchPlan(actions, expectedRpo, true);
    }
}
