package io.tieringkv.storage.memory;

import io.tieringkv.operations.KeyspaceNotifications;
import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.session.ConnectionContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** keyspace 过期通知（ADR-0294）。 */
class KeyspaceExpiryNotificationTest {

    @AfterEach
    void restore() {
        KeyspaceNotifications.setEnabled(true);
    }

    @Test
    void immediateExpirePublishesNotification() {
        PubSubBroker broker = ConnectionContext.sharedBroker();
        List<String> received = new CopyOnWriteArrayList<>();
        broker.subscribe("__keyspace@0__:k",
                (channel, message) -> received.add(new String(
                        message, StandardCharsets.UTF_8)));
        MemTable table = MemTable.create();
        table.put("k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8));
        table.put("k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(received).contains("expired");
    }

    @Test
    void disabledNotificationsSilent() {
        KeyspaceNotifications.setEnabled(false);
        PubSubBroker broker = ConnectionContext.sharedBroker();
        List<String> received = new CopyOnWriteArrayList<>();
        broker.subscribe("__keyspace@0__:k",
                (channel, message) -> received.add("x"));
        MemTable table = MemTable.create();
        table.put("k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8));
        table.put("k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(received).isEmpty();
    }

    @Test
    void enabledFlagRoundTrip() {
        assertThat(KeyspaceNotifications.enabled()).isTrue();
        KeyspaceNotifications.setEnabled(false);
        assertThat(KeyspaceNotifications.enabled()).isFalse();
    }

    @Test
    void activeExpirePublishesNotification() throws Exception {
        PubSubBroker broker = ConnectionContext.sharedBroker();
        List<String> received = new CopyOnWriteArrayList<>();
        broker.subscribe("__keyspace@0__:k",
                (channel, message) -> received.add("expired"));
        MemTable table = MemTable.create();
        table.put("k".getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8), 1);
        Thread.sleep(20);
        table.activeExpire();
        assertThat(received).isNotEmpty();
    }

    @ParameterizedTest(name = "key {0}")
    @MethodSource("keys")
    void publishChannelPerKey(String key) {
        PubSubBroker broker = ConnectionContext.sharedBroker();
        List<String> received = new CopyOnWriteArrayList<>();
        broker.subscribe("__keyspace@0__:" + key,
                (channel, message) -> received.add("expired"));
        MemTable table = MemTable.create();
        table.put(key.getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8));
        table.put(key.getBytes(StandardCharsets.UTF_8),
                "v".getBytes(StandardCharsets.UTF_8), 0);
        assertThat(received).contains("expired");
    }

    static Stream<Arguments> keys() {
        return Stream.of("a", "user:1", "中文", "key.with:chars",
                        "x-y")
                .map(Arguments::of);
    }
}
