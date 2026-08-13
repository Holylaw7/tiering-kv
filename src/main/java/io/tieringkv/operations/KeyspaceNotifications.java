package io.tieringkv.operations;

import io.tieringkv.session.ConnectionContext;

import java.nio.charset.StandardCharsets;

/** keyspace 过期通知（ADR-0294）：发布到共享 broker，开关可控。 */
public final class KeyspaceNotifications {

    private static volatile boolean enabled = true;

    private KeyspaceNotifications() {
    }

    public static void setEnabled(boolean enabled) {
        KeyspaceNotifications.enabled = enabled;
    }

    public static boolean enabled() {
        return enabled;
    }

    public static void publishExpired(byte[] key) {
        if (!enabled) {
            return;
        }
        String channel = "__keyspace@0__:"
                + new String(key, StandardCharsets.UTF_8);
        ConnectionContext.sharedBroker().publish(channel,
                "expired".getBytes(StandardCharsets.UTF_8));
    }
}
