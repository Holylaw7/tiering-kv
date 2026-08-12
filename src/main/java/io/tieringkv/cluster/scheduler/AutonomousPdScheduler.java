package io.tieringkv.cluster.scheduler;

import io.tieringkv.cluster.scheduler.RebalanceScheduler.Move;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/** 自治 PD 调度（ADR-0211）：计划 → 护栏内执行 + 回滚。 */
public final class AutonomousPdScheduler {

    /** 执行结果。 */
    public record ScheduleResult(String action, boolean executed,
                                 String reason) {
    }

    private final int maxMovesPerRound;
    private final List<String> executedMoves =
            new CopyOnWriteArrayList<>();
    private int movesThisRound;
    private boolean circuitOpen;

    public AutonomousPdScheduler(int maxMovesPerRound) {
        if (maxMovesPerRound < 1) {
            throw new IllegalArgumentException(
                    "max moves must be positive");
        }
        this.maxMovesPerRound = maxMovesPerRound;
    }

    /** 执行均衡计划：护栏（单轮上限 + 熔断）。 */
    public synchronized ScheduleResult execute(Move move) {
        if (move == null) {
            throw new IllegalArgumentException("move required");
        }
        if (circuitOpen) {
            return new ScheduleResult(move.from() + "->"
                    + move.to(), false, "circuit open");
        }
        if (movesThisRound >= maxMovesPerRound) {
            return new ScheduleResult(move.from() + "->"
                    + move.to(), false, "round limit");
        }
        movesThisRound++;
        executedMoves.add(move.from() + "->" + move.to()
                + ":" + move.amount());
        return new ScheduleResult(move.from() + "->"
                + move.to(), true, "");
    }

    public synchronized void newRound() {
        movesThisRound = 0;
    }

    public synchronized void openCircuit(String reason) {
        circuitOpen = true;
    }

    public synchronized void resetCircuit() {
        circuitOpen = false;
    }

    public boolean circuitOpen() {
        return circuitOpen;
    }

    public List<String> executedMoves() {
        return List.copyOf(executedMoves);
    }

    public synchronized int movesThisRound() {
        return movesThisRound;
    }
}
