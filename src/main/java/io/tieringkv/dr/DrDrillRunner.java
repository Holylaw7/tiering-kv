package io.tieringkv.dr;

/** 容灾演练（ADR-0115）：执行切换并采样 RTO/RPO。 */
public final class DrDrillRunner {

    public record DrillResult(long rtoMillis, long rpoMillis,
                              boolean success) {
    }

    public DrillResult run(SwitchPlan plan,
                           java.util.function.BooleanSupplier promoteOk,
                           long simulatedSwitchMillis) {
        long start = System.nanoTime();
        boolean ok = promoteOk.getAsBoolean();
        long rto = (System.nanoTime() - start) / 1_000_000
                + simulatedSwitchMillis;
        return new DrillResult(rto, plan.expectedRpoMillis(), ok);
    }
}
