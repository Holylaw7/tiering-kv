package io.tieringkv.pubsub;

import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArraySet;

/**
 * 本地 Pub/Sub broker（ADR-0282）：channel/pattern 订阅 +
 * 至少一次投递；集群广播经 PubSubForwarder SPI。
 */
public final class PubSubBroker {

    private final Map<String, Set<Subscriber>> channels =
            new ConcurrentHashMap<>();
    private final Map<String, Set<Subscriber>> patterns =
            new ConcurrentHashMap<>();
    private volatile PubSubForwarder forwarder =
            PubSubForwarder.noop();
    private volatile String nodeId = "local";

    public void attachForwarder(PubSubForwarder forwarder,
                                String nodeId) {
        if (forwarder == null || nodeId == null
                || nodeId.isBlank()) {
            throw new IllegalArgumentException(
                    "forwarder and nodeId required");
        }
        this.forwarder = forwarder;
        this.nodeId = nodeId;
    }

    public int subscribe(String channel, Subscriber subscriber) {
        validate(channel, subscriber);
        channels.computeIfAbsent(channel,
                ignored -> new CopyOnWriteArraySet<>())
                .add(subscriber);
        return channels.get(channel).size();
    }

    public int unsubscribe(String channel,
                           Subscriber subscriber) {
        validate(channel, subscriber);
        Set<Subscriber> set = channels.get(channel);
        if (set == null) {
            return 0;
        }
        set.remove(subscriber);
        if (set.isEmpty()) {
            channels.remove(channel);
        }
        return 0;
    }

    public int psubscribe(String pattern,
                          Subscriber subscriber) {
        validate(pattern, subscriber);
        patterns.computeIfAbsent(pattern,
                ignored -> new CopyOnWriteArraySet<>())
                .add(subscriber);
        return patterns.get(pattern).size();
    }

    public int punsubscribe(String pattern,
                            Subscriber subscriber) {
        validate(pattern, subscriber);
        Set<Subscriber> set = patterns.get(pattern);
        if (set != null) {
            set.remove(subscriber);
            if (set.isEmpty()) {
                patterns.remove(pattern);
            }
        }
        return 0;
    }

    /** 发布：投递给直接订阅者 + 匹配模式订阅者（快照，至少一次）。 */
    public int publish(String channel, byte[] message) {
        if (channel == null || channel.isBlank()
                || message == null) {
            throw new IllegalArgumentException(
                    "channel and message required");
        }
        int receivers = 0;
        Set<Subscriber> direct = channels.get(channel);
        if (direct != null) {
            for (Subscriber subscriber : direct) {
                subscriber.onMessage(channel, message);
                receivers++;
            }
        }
        for (Map.Entry<String, Set<Subscriber>> entry
                : patterns.entrySet()) {
            if (patternMatches(entry.getKey(), channel)) {
                for (Subscriber subscriber : entry.getValue()) {
                    subscriber.onMessage(channel, message);
                    receivers++;
                }
            }
        }
        forwarder.forward(nodeId, channel, message);
        return receivers;
    }

    public int subscriberCount(String channel) {
        Set<Subscriber> set = channels.get(channel);
        return set == null ? 0 : set.size();
    }

    public int patternCount(String pattern) {
        Set<Subscriber> set = patterns.get(pattern);
        return set == null ? 0 : set.size();
    }

    /** 通配：* 匹配任意串，? 匹配单字符（与 SCAN 一致）。 */
    static boolean patternMatches(String pattern,
                                  String channel) {
        return java.util.regex.Pattern.compile(
                        globToRegex(pattern))
                .matcher(channel).matches();
    }

    private static String globToRegex(String pattern) {
        StringBuilder builder = new StringBuilder();
        for (char c : pattern.toCharArray()) {
            switch (c) {
                case '*' -> builder.append(".*");
                case '?' -> builder.append('.');
                default -> builder.append(
                        java.util.regex.Pattern.quote(
                                String.valueOf(c)));
            }
        }
        return builder.toString();
    }

    private static void validate(String name,
                                 Subscriber subscriber) {
        if (name == null || name.isBlank()
                || subscriber == null) {
            throw new IllegalArgumentException(
                    "name and subscriber required");
        }
    }
}
