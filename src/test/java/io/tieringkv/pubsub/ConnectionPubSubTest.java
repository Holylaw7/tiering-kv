package io.tieringkv.pubsub;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.function.Supplier;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Pub/Sub 连接级投递（ADR-0284/0288）。 */
class ConnectionPubSubTest {

    private static <T> T withContext(ConnectionContext context,
                                     Supplier<T> action) {
        ConnectionContext.attach(context);
        try {
            return action.get();
        } finally {
            ConnectionContext.detach();
        }
    }

    @Test
    void connectionSubscriberReceivesMessages() {
        ConnectionSubscriber subscriber =
                new ConnectionSubscriber();
        PubSubBroker broker = new PubSubBroker();
        broker.subscribe("news", subscriber);
        broker.publish("news", "hello".getBytes(
                StandardCharsets.UTF_8));
        ConnectionSubscriber.Message message =
                subscriber.poll();
        assertThat(message.channel()).isEqualTo("news");
        assertThat(message.payload()).isEqualTo(
                "hello".getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void boundedQueueDropsOldest() {
        ConnectionSubscriber subscriber =
                new ConnectionSubscriber(3);
        PubSubBroker broker = new PubSubBroker();
        broker.subscribe("c", subscriber);
        for (int i = 0; i < 5; i++) {
            broker.publish("c", Integer.toString(i).getBytes(
                    StandardCharsets.UTF_8));
        }
        assertThat(subscriber.size()).isEqualTo(3);
        assertThat(subscriber.dropped()).isEqualTo(2);
    }

    @Test
    void commandSubscribeBindsConnectionSubscriber() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("subscribe", "news");
            return null;
        });
        assertThat(ConnectionContext.sharedBroker()
                .subscriberCount("news")).isGreaterThan(0);
        ConnectionContext.sharedBroker().publish("news",
                "m".getBytes(StandardCharsets.UTF_8));
        assertThat(context.subscriber().size()).isEqualTo(1);
    }

    @Test
    void commandPsubscribeBindsPattern() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("psubscribe", "user:*");
            return null;
        });
        ConnectionContext.sharedBroker().publish("user:1",
                "m".getBytes(StandardCharsets.UTF_8));
        assertThat(context.subscriber().size()).isEqualTo(1);
    }

    @Test
    void cleanupUnsubscribesAll() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("subscribe", "a", "b");
            runner.exec("psubscribe", "x*");
            return null;
        });
        context.cleanup();
        assertThat(ConnectionContext.sharedBroker()
                .subscriberCount("a")).isZero();
        assertThat(ConnectionContext.sharedBroker()
                .subscriberCount("b")).isZero();
        assertThat(ConnectionContext.sharedBroker()
                .patternCount("x*")).isZero();
    }

    @Test
    void unsubscribeAllRemovesOnlyThatSubscriber() {
        PubSubBroker broker = new PubSubBroker();
        ConnectionSubscriber one = new ConnectionSubscriber();
        ConnectionSubscriber two = new ConnectionSubscriber();
        broker.subscribe("c", one);
        broker.subscribe("c", two);
        broker.unsubscribeAll(one);
        assertThat(broker.subscriberCount("c")).isEqualTo(1);
        broker.unsubscribeAll(two);
        assertThat(broker.subscriberCount("c")).isZero();
    }

    @Test
    void messageAfterCleanupNotDelivered() {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        withContext(context, () -> {
            runner.exec("subscribe", "c");
            return null;
        });
        context.cleanup();
        ConnectionContext.sharedBroker().publish("c",
                "m".getBytes(StandardCharsets.UTF_8));
        assertThat(context.subscriber().size()).isZero();
    }

    @ParameterizedTest(name = "capacity {0}")
    @MethodSource("capacities")
    void capacityMatrix(int capacity) {
        ConnectionSubscriber subscriber =
                new ConnectionSubscriber(capacity);
        PubSubBroker broker = new PubSubBroker();
        broker.subscribe("c", subscriber);
        for (int i = 0; i < capacity * 2; i++) {
            broker.publish("c", new byte[0]);
        }
        assertThat(subscriber.size()).isEqualTo(capacity);
        assertThat(subscriber.dropped())
                .isEqualTo(capacity);
    }

    @ParameterizedTest(name = "channels {0}")
    @MethodSource("channelCounts")
    void multiChannelSubscribe(int channels) {
        ConnectionContext context = new ConnectionContext();
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        Object[] args = new Object[channels];
        for (int i = 0; i < channels; i++) {
            args[i] = "ch" + i;
        }
        withContext(context, () -> runner.exec("subscribe",
                args));
        for (int i = 0; i < channels; i++) {
            ConnectionContext.sharedBroker().publish("ch" + i,
                    "m".getBytes(StandardCharsets.UTF_8));
        }
        assertThat(context.subscriber().size())
                .isEqualTo(channels);
    }

    static Stream<Arguments> capacities() {
        return Stream.of(1, 2, 5, 10, 64)
                .map(Arguments::of);
    }

    static Stream<Arguments> channelCounts() {
        return Stream.of(1, 2, 3, 5).map(Arguments::of);
    }
}
