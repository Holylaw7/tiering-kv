package io.tieringkv.cluster.scheduler;

import io.tieringkv.capacity.ai.ReinforcementAutonomy.Action;
import io.tieringkv.capacity.ai.TopologyFederatedAutonomy;
import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;
import io.tieringkv.cluster.topology.TopologyDiscovery;
import io.tieringkv.cluster.topology.TopologyDiscovery.NodeInfo;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自治 PD 与全球自治联动（ADR-0217）：拓扑变化 → 均衡计划 → 护栏内执行，
 * 失败回滚本轮。只调整策略参数，禁止放宽 Raft/一致性约束。
 */
public final class GlobalAutonomyPdIntegration {

    /** 执行钩子：测试可注入失败以验证回滚语义。 */
    @FunctionalInterface
    public interface MoveValidator {
        boolean allow(Move move);
    }

    /** 联动计划结果。 */
    public record PlanResult(long topologyVersion, List<Move> plan,
                             int executed, boolean rolledBack,
                             List<String> guardrailReasons) {
    }

    /** 审计条目。 */
    public record AuditEntry(String type, String detail) {
    }

    /** 政策冻结阈值：全局自治 TIGHTEN 权重超过该值则本轮不执行调度。 */
    private static final double POLICY_FREEZE_TIGHTEN_WEIGHT = 0.6;

    private final TopologyDiscovery discovery;
    private final AutonomousPdScheduler scheduler;
    private final TopologyFederatedAutonomy autonomy;
    private final RebalanceScheduler rebalancer =
            new RebalanceScheduler();
    private final long maxLoad;
    private final MoveValidator validator;
    private final List<AuditEntry> audit =
            new CopyOnWriteArrayList<>();
    private final List<Move> pendingMoves = new ArrayList<>();
    private long topologyVersion;
    private int healthyNodesAtLastPlan = -1;

    public GlobalAutonomyPdIntegration(
            TopologyDiscovery discovery,
            AutonomousPdScheduler scheduler,
            TopologyFederatedAutonomy autonomy,
            long maxLoad) {
        this(discovery, scheduler, autonomy, maxLoad, move -> true);
    }

    public GlobalAutonomyPdIntegration(
            TopologyDiscovery discovery,
            AutonomousPdScheduler scheduler,
            TopologyFederatedAutonomy autonomy,
            long maxLoad, MoveValidator validator) {
        if (discovery == null || scheduler == null
                || autonomy == null || validator == null
                || maxLoad <= 0) {
            throw new IllegalArgumentException(
                    "discovery/scheduler/autonomy/validator/maxLoad "
                            + "required and maxLoad must be positive");
        }
        this.discovery = discovery;
        this.scheduler = scheduler;
        this.autonomy = autonomy;
        this.maxLoad = maxLoad;
        this.validator = validator;
    }

    /** 检测拓扑变化：健康节点数变化 → 版本递增（动态拓扑学习）。 */
    public synchronized long detectTopologyChange() {
        long healthy = discovery.nodes().stream()
                .filter(NodeInfo::healthy).count();
        if (healthy != healthyNodesAtLastPlan) {
            topologyVersion++;
            healthyNodesAtLastPlan = (int) healthy;
            audit.add(new AuditEntry("TOPOLOGY",
                    "healthy nodes -> " + healthy));
        }
        return topologyVersion;
    }

    public long topologyVersion() {
        return topologyVersion;
    }

    /**
     * 联动执行：负载 → 均衡计划 → 政策/地域/AZ/执行钩子护栏；
     * 任一护栏拦截时回滚本轮已执行动作并记录审计。
     */
    public synchronized PlanResult planAndExecute(
            Map<String, Long> loads) {
        if (loads == null || loads.isEmpty()) {
            throw new IllegalArgumentException(
                    "loads required");
        }
        detectTopologyChange();
        List<Move> plan = rebalancer.plan(loads, maxLoad);
        pendingMoves.clear();
        List<String> reasons = new ArrayList<>();
        int executed = 0;
        boolean rolledBack = false;

        Map<Action, Double> weights = autonomy.aggregate();
        if (weights.getOrDefault(Action.TIGHTEN, 0.0)
                > POLICY_FREEZE_TIGHTEN_WEIGHT) {
            reasons.add("policy freeze: tighten weight > 0.6");
            audit.add(new AuditEntry("GUARDRAIL",
                    reasons.get(0)));
            return new PlanResult(topologyVersion,
                    List.copyOf(plan), 0, false,
                    List.copyOf(reasons));
        }

        for (Move move : plan) {
            if (!regionQuorumHolds(move)
                    || !azSpreadHolds(move)
                    || !validator.allow(move)) {
                reasons.add("blocked: " + move);
                rolledBack = rollbackPending();
                break;
            }
            var result = scheduler.execute(move);
            if (!result.executed()) {
                reasons.add("scheduler: " + result.reason());
                rolledBack = rollbackPending();
                break;
            }
            pendingMoves.add(move);
            executed++;
        }
        if (rolledBack) {
            audit.add(new AuditEntry("ROLLBACK",
                    "reverted moves=" + pendingMoves.size()));
        } else if (!reasons.isEmpty()) {
            audit.add(new AuditEntry("GUARDRAIL",
                    String.join("; ", reasons)));
        } else {
            audit.add(new AuditEntry("EXECUTED",
                    "moves=" + executed));
        }
        scheduler.newRound();
        return new PlanResult(topologyVersion,
                List.copyOf(plan), executed, rolledBack,
                List.copyOf(reasons));
    }

    /** 回滚本轮已执行动作（进程内审计语义；真实迁移回滚由迁移层保证）。 */
    private boolean rollbackPending() {
        if (pendingMoves.isEmpty()) {
            return false;
        }
        pendingMoves.clear();
        return true;
    }

    /** 地域护栏：源节点所在地域必须保留至少 2 个健康节点。 */
    private boolean regionQuorumHolds(Move move) {
        List<NodeInfo> nodes = discovery.nodes();
        return nodes.stream()
                .filter(node -> node.nodeId().equals(move.from()))
                .findFirst()
                .map(source -> nodes.stream()
                        .filter(node -> node.region()
                                .equals(source.region())
                                && node.healthy())
                        .count() > 1)
                .orElse(true);
    }

    /** AZ 护栏：源节点所在 AZ 必须保留至少 2 个健康节点。 */
    private boolean azSpreadHolds(Move move) {
        List<NodeInfo> nodes = discovery.nodes();
        return nodes.stream()
                .filter(node -> node.nodeId().equals(move.from()))
                .findFirst()
                .map(source -> nodes.stream()
                        .filter(node -> node.availabilityZone()
                                .equals(source.availabilityZone())
                                && node.healthy())
                        .count() > 1)
                .orElse(true);
    }

    public List<AuditEntry> audit() {
        return List.copyOf(audit);
    }
}
