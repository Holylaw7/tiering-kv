package io.tieringkv.operator;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * 多集群复制计划器（ADR-0322 M4）：期望拓扑 vs 当前连接 → 动作列表
 * （CONNECT / DISCONNECT / NOOP），供 Controller 接线 M3 复制通道。
 */
public final class MultiClusterPlanner {

    public record ReplicationEdgeAction(ActionType type,
                                        MultiClusterTopology
                                                .ReplicationEdge edge) {
        public enum ActionType { CONNECT, DISCONNECT, NOOP }
    }

    public List<ReplicationEdgeAction> plan(
            MultiClusterTopology desired,
            List<MultiClusterTopology.ReplicationEdge> current) {
        if (current == null) {
            throw new IllegalArgumentException("current required");
        }
        List<ReplicationEdgeAction> actions = new ArrayList<>();
        Set<MultiClusterTopology.ReplicationEdge> desiredSet =
                new HashSet<>(desired.edges());
        Set<MultiClusterTopology.ReplicationEdge> currentSet =
                new HashSet<>(current);

        for (MultiClusterTopology.ReplicationEdge edge :
                desired.edges()) {
            if (!currentSet.contains(edge)) {
                actions.add(new ReplicationEdgeAction(
                        ReplicationEdgeAction.ActionType.CONNECT,
                        edge));
            }
        }
        for (MultiClusterTopology.ReplicationEdge edge : current) {
            if (!desiredSet.contains(edge)) {
                actions.add(new ReplicationEdgeAction(
                        ReplicationEdgeAction.ActionType.DISCONNECT,
                        edge));
            }
        }
        if (actions.isEmpty()) {
            actions.add(new ReplicationEdgeAction(
                    ReplicationEdgeAction.ActionType.NOOP,
                    desired.edges().isEmpty() ? null
                            : desired.edges().get(0)));
        }
        return List.copyOf(actions);
    }
}
