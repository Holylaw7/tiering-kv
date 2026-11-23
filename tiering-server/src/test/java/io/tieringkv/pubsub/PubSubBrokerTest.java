package io.tieringkv.pubsub;

import io.tieringkv.command.TestCommandRunner;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** Pub/Sub 本地 broker（ADR-0282）。 */
class PubSubBrokerTest {

    @Test
    void directSubscriptionReceivesMessage() {
        PubSubBroker broker = new PubSubBroker();
        List<String> received = new CopyOnWriteArrayList<>();
        broker.subscribe("news", (channel, message) ->
                received.add(new String(message,
                        StandardCharsets.UTF_8)));
        int receivers = broker.publish("news",
                "hello".getBytes(StandardCharsets.UTF_8));
        assertThat(receivers).isEqualTo(1);
        assertThat(received).containsExactly("hello");
    }

    @Test
    void patternSubscriptionMatches() {
        PubSubBroker broker = new PubSubBroker();
        List<String> channels = new CopyOnWriteArrayList<>();
        broker.psubscribe("user:*", (channel, message) ->
                channels.add(channel));
        broker.publish("user:1", new byte[0]);
        broker.publish("user:2", new byte[0]);
        broker.publish("order:1", new byte[0]);
        assertThat(channels).containsExactly("user:1", "user:2");
    }

    @Test
    void unsubscribeStopsDelivery() {
        PubSubBroker broker = new PubSubBroker();
        AtomicInteger count = new AtomicInteger();
        Subscriber subscriber = (channel, message) ->
                count.incrementAndGet();
        broker.subscribe("c", subscriber);
        broker.publish("c", new byte[0]);
        broker.unsubscribe("c", subscriber);
        broker.publish("c", new byte[0]);
        assertThat(count.get()).isEqualTo(1);
    }

    @Test
    void subscriberCountReflects() {
        PubSubBroker broker = new PubSubBroker();
        Subscriber one = (c, m) -> {
        };
        Subscriber two = (c, m) -> {
        };
        broker.subscribe("c", one);
        broker.subscribe("c", two);
        assertThat(broker.subscriberCount("c")).isEqualTo(2);
        broker.unsubscribe("c", one);
        assertThat(broker.subscriberCount("c")).isEqualTo(1);
    }

    @Test
    void publishToNoSubscribersReturnsZero() {
        PubSubBroker broker = new PubSubBroker();
        assertThat(broker.publish("none", new byte[0])).isZero();
    }

    @Test
    void forwarderInvokedOnPublish() {
        PubSubBroker broker = new PubSubBroker();
        List<String> forwarded = new CopyOnWriteArrayList<>();
        broker.attachForwarder((node, channel, message) ->
                forwarded.add(node + ":" + channel), "n1");
        broker.publish("c", new byte[0]);
        assertThat(forwarded).containsExactly("n1:c");
    }

    @Test
    void commandPublishReturnsReceiverCount() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        // 通过命令注册默认订阅者
        runner.exec("subscribe", "ch");
        RespValue result = runner.exec("publish", "ch", "msg");
        assertThat(result).isInstanceOf(RespInteger.class);
        assertThat(((RespInteger) result).value())
                .isGreaterThanOrEqualTo(1);
    }

    @Test
    void subscribeCommandReturnsConfirmation() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        runner.exec("subscribe", "news");
        RespValue result = runner.exec("publish", "news", "m");
        assertThat(result).isInstanceOf(RespInteger.class);
    }

    @Test
    void subscribeConfirmationArrayShape() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("subscribe", "a", "b");
        RespArray array = (RespArray) result;
        assertThat(array.values()).hasSize(2);
        RespArray first = (RespArray) array.values().get(0);
        assertThat(((RespBulkString) first.values().get(0))
                .bytes()).isEqualTo("subscribe".getBytes(
                StandardCharsets.UTF_8));
    }

    @Test
    void psubscribeConfirmationShape() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("psubscribe", "user:*");
        RespArray array = (RespArray) result;
        assertThat(((RespBulkString) ((RespArray) array
                .values().get(0)).values().get(0)).bytes())
                .isEqualTo("psubscribe".getBytes(
                        StandardCharsets.UTF_8));
    }

    @Test
    void unsubscribeConfirmation() {
        TestCommandRunner runner =
                new TestCommandRunner(MemTable.create());
        RespValue result = runner.exec("unsubscribe", "a");
        assertThat(result).isInstanceOf(RespArray.class);
    }

    @Test
    void concurrentPublishAtLeastOnce() throws Exception {
        PubSubBroker broker = new PubSubBroker();
        AtomicInteger received = new AtomicInteger();
        broker.subscribe("c", (channel, message) ->
                received.incrementAndGet());
        int publishers = 8;
        int messages = 100;
        CountDownLatch start = new CountDownLatch(1);
        Thread[] threads = new Thread[publishers];
        for (int t = 0; t < publishers; t++) {
            threads[t] = new Thread(() -> {
                try {
                    start.await();
                    for (int i = 0; i < messages; i++) {
                        broker.publish("c", new byte[0]);
                    }
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            });
            threads[t].start();
        }
        start.countDown();
        for (Thread thread : threads) {
            thread.join(10_000);
        }
        assertThat(received.get()).isEqualTo(publishers * messages);
    }

    @ParameterizedTest(name = "pattern {0} matches {1}")
    @MethodSource("patternMatrix")
    void patternMatchMatrix(String pattern, String channel,
                            boolean expected) {
        assertThat(PubSubBroker.patternMatches(pattern, channel))
                .isEqualTo(expected);
    }

    @ParameterizedTest(name = "publish receivers {0}")
    @MethodSource("receiverMatrix")
    void receiverCountMatrix(int direct, int patterns) {
        PubSubBroker broker = new PubSubBroker();
        for (int i = 0; i < direct; i++) {
            final int index = i;
            broker.subscribe("ch", (c, m) -> {
                int ignored = index;
            });
        }
        for (int i = 0; i < patterns; i++) {
            final int index = i;
            broker.psubscribe("c*", (c, m) -> {
                int ignored = index;
            });
        }
        int receivers = broker.publish("ch", new byte[0]);
        assertThat(receivers).isEqualTo(direct + patterns);
    }

    static Stream<Arguments> patternMatrix() {
        return Stream.of(
                Arguments.of("*", "any", true),
                Arguments.of("user:*", "user:1", true),
                Arguments.of("user:*", "order:1", false),
                Arguments.of("user:?", "user:1", true),
                Arguments.of("user:?", "user:12", false),
                Arguments.of("a*b", "acb", true),
                Arguments.of("a*b", "ab", true),
                Arguments.of("a*b", "ac", false),
                Arguments.of("x", "x", true),
                Arguments.of("x", "y", false),
                Arguments.of("*:*", "a:b", true),
                Arguments.of("*:*", "ab", false),
                Arguments.of("news.*", "news.tech", true),
                Arguments.of("news.*", "sports", false));
    }

    static Stream<Arguments> receiverMatrix() {
        return Stream.of(
                Arguments.of(0, 0),
                Arguments.of(1, 0),
                Arguments.of(0, 1),
                Arguments.of(1, 1),
                Arguments.of(3, 2),
                Arguments.of(2, 3));
    }
}
