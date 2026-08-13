package io.tieringkv.session;

import io.tieringkv.command.RespCommand;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.pubsub.ConnectionSubscriber;
import io.tieringkv.pubsub.PubSubBroker;
import io.tieringkv.storage.types.ByteArrayKey;

import java.util.HashMap;
import java.util.Map;
import java.util.ArrayList;
import java.util.List;

/**
 * 连接级上下文（ADR-0283/0287）：协议版本 + Pub/Sub 订阅 + 事务队列。
 * 由网络处理器在事件循环内设置 ThreadLocal，命令层只读访问。
 */
public final class ConnectionContext {

    private static final ThreadLocal<ConnectionContext> CURRENT =
            new ThreadLocal<>();
    private static final PubSubBroker SHARED_BROKER =
            new PubSubBroker();

    private RespVersion version = RespVersion.RESP2;
    private final ConnectionSubscriber subscriber =
            new ConnectionSubscriber();
    private boolean inMulti;
    private final List<RespCommand> txnQueue = new ArrayList<>();
    private final Map<ByteArrayKey, Long> watched = new HashMap<>();

    public static ConnectionContext enter() {
        ConnectionContext context = new ConnectionContext();
        CURRENT.set(context);
        return context;
    }

    public static ConnectionContext current() {
        return CURRENT.get();
    }

    public static void exit() {
        CURRENT.remove();
    }

    /** 将指定上下文绑定到当前线程（异步 worker 用）。 */
    public static ConnectionContext attach(
            ConnectionContext context) {
        if (context == null) {
            throw new IllegalArgumentException(
                    "context required");
        }
        CURRENT.set(context);
        return context;
    }

    public static void detach() {
        CURRENT.remove();
    }

    public static PubSubBroker sharedBroker() {
        return SHARED_BROKER;
    }

    public RespVersion version() {
        return version;
    }

    public void setVersion(RespVersion version) {
        if (version == null) {
            throw new IllegalArgumentException(
                    "version required");
        }
        this.version = version;
    }

    public ConnectionSubscriber subscriber() {
        return subscriber;
    }

    public boolean inMulti() {
        return inMulti;
    }

    public void setInMulti(boolean inMulti) {
        this.inMulti = inMulti;
    }

    public void enqueue(RespCommand command) {
        if (command == null) {
            throw new IllegalArgumentException(
                    "command required");
        }
        txnQueue.add(command);
    }

    public List<RespCommand> txnQueue() {
        return List.copyOf(txnQueue);
    }

    public void clearTxnQueue() {
        txnQueue.clear();
        inMulti = false;
    }

    public void watch(ByteArrayKey key, long version) {
        watched.put(key, version);
    }

    public Map<ByteArrayKey, Long> watched() {
        return Map.copyOf(watched);
    }

    public void unwatchAll() {
        watched.clear();
    }

    /** EXEC 前校验：全部被观察键版本一致。 */
    public boolean versionsMatch(
            java.util.function.Function<byte[], Long> versionOf) {
        for (Map.Entry<ByteArrayKey, Long> entry
                : watched.entrySet()) {
            long current = versionOf.apply(entry.getKey().data());
            if (current != entry.getValue()) {
                return false;
            }
        }
        return true;
    }

    /** 连接关闭清理：退订 + 清空事务 + 重置协议版本。 */
    public void cleanup() {
        SHARED_BROKER.unsubscribeAll(subscriber);
        clearTxnQueue();
        unwatchAll();
        version = RespVersion.RESP2;
        subscriber.clear();
    }
}
