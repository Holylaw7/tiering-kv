package io.tieringkv.operator;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 集群状态机（ADR-0322）：Provisioning → Ready → Upgrading /
 * BackingUp / Restoring，任意阶段可进 FAILED。
 *
 * <p>转换矩阵：
 * <ul>
 *   <li>PENDING → PROVISIONING / FAILED</li>
 *   <li>PROVISIONING → READY / FAILED</li>
 *   <li>READY → UPGRADING / BACKING_UP / RESTORING / FAILED</li>
 *   <li>UPGRADING / BACKING_UP / RESTORING → READY / FAILED</li>
 *   <li>FAILED → PENDING（人工重试）</li>
 * </ul>
 */
public final class ClusterStateMachine {

    private static final Map<ClusterPhase, Set<ClusterPhase>>
            TRANSITIONS = new EnumMap<>(ClusterPhase.class);

    static {
        TRANSITIONS.put(ClusterPhase.PENDING, EnumSet.of(
                ClusterPhase.PROVISIONING, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.PROVISIONING, EnumSet.of(
                ClusterPhase.READY, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.READY, EnumSet.of(
                ClusterPhase.UPGRADING, ClusterPhase.BACKING_UP,
                ClusterPhase.RESTORING, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.UPGRADING, EnumSet.of(
                ClusterPhase.READY, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.BACKING_UP, EnumSet.of(
                ClusterPhase.READY, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.RESTORING, EnumSet.of(
                ClusterPhase.READY, ClusterPhase.FAILED));
        TRANSITIONS.put(ClusterPhase.FAILED, EnumSet.of(
                ClusterPhase.PENDING));
    }

    private ClusterPhase current;

    public ClusterStateMachine(ClusterPhase initial) {
        if (initial == null) {
            throw new IllegalArgumentException("initial required");
        }
        this.current = initial;
    }

    public ClusterPhase current() {
        return current;
    }

    /** 执行转换；非法转换抛 IllegalArgumentException。 */
    public ClusterPhase transition(ClusterPhase target) {
        if (target == null) {
            throw new IllegalArgumentException("target required");
        }
        Set<ClusterPhase> allowed = TRANSITIONS.get(current);
        if (allowed == null || !allowed.contains(target)) {
            throw new IllegalArgumentException(
                    "illegal transition " + current + " -> " + target);
        }
        current = target;
        return current;
    }

    public static boolean canTransition(ClusterPhase from,
                                        ClusterPhase to) {
        Set<ClusterPhase> allowed = TRANSITIONS.get(from);
        return allowed != null && allowed.contains(to);
    }

    public static Set<ClusterPhase> allowedFrom(ClusterPhase phase) {
        return Set.copyOf(TRANSITIONS.getOrDefault(
                phase, EnumSet.noneOf(ClusterPhase.class)));
    }
}
