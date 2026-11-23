package io.tieringkv.saas.commerce;

/** SaaS 订阅状态机（ADR-0146）：TRIAL → ACTIVE → CANCELED。 */
public final class Subscription {

    private Subscription() {
    }

    public enum State {
        TRIAL,
        ACTIVE,
        CANCELED
    }

    /** 订阅快照：不可变，转换返回新快照。 */
    public record Snapshot(String tenantId, String planId, State state,
                           long cycle, long startMillis) {

        public Snapshot {
            if (tenantId == null || tenantId.isBlank()) {
                throw new IllegalArgumentException(
                        "tenantId required");
            }
            if (planId == null || planId.isBlank()) {
                throw new IllegalArgumentException("planId required");
            }
            if (cycle < 0) {
                throw new IllegalArgumentException(
                        "cycle must be non-negative");
            }
        }

        public Snapshot activate() {
            if (state == State.CANCELED) {
                throw new IllegalStateException(
                        "canceled subscription cannot be activated");
            }
            return new Snapshot(tenantId, planId, State.ACTIVE,
                    cycle, startMillis);
        }

        public Snapshot cancel() {
            return new Snapshot(tenantId, planId, State.CANCELED,
                    cycle, startMillis);
        }

        public Snapshot renew() {
            if (state != State.ACTIVE) {
                throw new IllegalStateException(
                        "only active subscriptions renew");
            }
            return new Snapshot(tenantId, planId, State.ACTIVE,
                    cycle + 1, startMillis);
        }
    }
}
