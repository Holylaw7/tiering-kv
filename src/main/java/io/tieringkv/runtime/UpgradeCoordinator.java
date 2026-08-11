package io.tieringkv.runtime;

import java.util.List;
import java.util.function.BooleanSupplier;
import java.util.function.Consumer;

/** 滚动升级（ADR-0098）：逐节点升级 + 追平等待，quorum 保持。 */
public final class UpgradeCoordinator {

    private UpgradeCoordinator() {
    }

    public static <T> boolean rollingUpgrade(List<T> nodes,
                                             Consumer<T> upgrade,
                                             BooleanSupplier quorumHealthy,
                                             BooleanSupplier caughtUp,
                                             long waitMillis) {
        for (T node : nodes) {
            if (!quorumHealthy.getAsBoolean()) {
                return false;
            }
            upgrade.accept(node);
            long deadline = System.currentTimeMillis() + waitMillis;
            boolean ok = false;
            while (System.currentTimeMillis() < deadline) {
                if (caughtUp.getAsBoolean()) {
                    ok = true;
                    break;
                }
                try {
                    Thread.sleep(10);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    return false;
                }
            }
            if (!ok) {
                return false;
            }
        }
        return true;
    }
}
