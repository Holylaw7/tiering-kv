package io.tieringkv.command;

import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.storage.StorageEngine;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

/**
 * Pub/Sub 命令（ADR-0282）：本地 broker + 默认队列订阅者；
 * 连接级投递接线 Phase 53+。
 */
public final class PubSubCommand implements Command {

    private final String name;
    private final PubSubBroker broker;

    public PubSubCommand(String name, PubSubBroker broker) {
        this.name = name;
        this.broker = broker;
    }

    @Override
    public String name() {
        return name;
    }

    @Override
    public RespValue execute(List<byte[]> args,
                             StorageEngine storage) {
        return switch (name) {
            case "publish" -> publish(args);
            case "subscribe" -> subscribe(args, false);
            case "unsubscribe" -> subscribe(args, true);
            case "psubscribe" -> pattern(args, false);
            case "punsubscribe" -> pattern(args, true);
            default -> RespError.unknownCommand(name);
        };
    }

    private RespValue publish(List<byte[]> args) {
        if (args.size() != 2) {
            return RespError.wrongArity(name);
        }
        int receivers = broker.publish(CommandUtil.text(args.get(0)),
                args.get(1));
        return new RespInteger(receivers);
    }

    private RespValue subscribe(List<byte[]> args,
                                boolean unsubscribe) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name);
        }
        List<RespValue> confirmations = new ArrayList<>();
        for (byte[] channel : args) {
            String name = CommandUtil.text(channel);
            if (unsubscribe) {
                broker.unsubscribe(name, sink());
            } else {
                broker.subscribe(name, sink());
            }
            confirmations.add(confirmation(
                    unsubscribe ? "unsubscribe" : "subscribe",
                    name, broker.subscriberCount(name)));
        }
        return new RespArray(confirmations);
    }

    private RespValue pattern(List<byte[]> args,
                              boolean unsubscribe) {
        if (args.isEmpty()) {
            return RespError.wrongArity(name);
        }
        List<RespValue> confirmations = new ArrayList<>();
        for (byte[] pattern : args) {
            String name = CommandUtil.text(pattern);
            if (unsubscribe) {
                broker.punsubscribe(name, sink());
            } else {
                broker.psubscribe(name, sink());
            }
            confirmations.add(confirmation(
                    unsubscribe ? "punsubscribe" : "psubscribe",
                    name, broker.patternCount(name)));
        }
        return new RespArray(confirmations);
    }

    private static RespArray confirmation(String kind,
                                          String name,
                                          int count) {
        return new RespArray(List.of(
                new RespBulkString(CommandUtil.bytes(kind)),
                new RespBulkString(CommandUtil.bytes(name)),
                new RespInteger(count)));
    }

    private static SubscriberSink sink() {
        return SubscriberSink.INSTANCE;
    }

    /** 默认队列订阅者：测试与 Phase 53 连接接线共用。 */
    public static final class SubscriberSink
            implements io.tieringkv.pubsub.Subscriber {

        private static final SubscriberSink INSTANCE =
                new SubscriberSink();
        private final BlockingQueue<Message> queue =
                new LinkedBlockingQueue<>();

        public record Message(String channel, byte[] payload) {
        }

        @Override
        public void onMessage(String channel, byte[] message) {
            queue.offer(new Message(channel, message.clone()));
        }

        public Message poll() {
            return queue.poll();
        }

        public int size() {
            return queue.size();
        }

        public void clear() {
            queue.clear();
        }
    }
}
