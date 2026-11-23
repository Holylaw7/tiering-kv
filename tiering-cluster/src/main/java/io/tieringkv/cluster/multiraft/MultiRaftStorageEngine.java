package io.tieringkv.cluster.multiraft;

import io.tieringkv.cluster.ReplicatedStorageEngine;
import io.tieringkv.cluster.raft.RaftNode;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.storage.AtomicStringOps;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.StorageIterator;
import io.tieringkv.storage.StorageRedirectException;
import io.tieringkv.storage.memory.KeyValueEntry;

import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.function.Supplier;
import java.util.function.UnaryOperator;

/**
 * Multi-Raft 分片存储适配器：按 Redis CRC16 HashSlot 将键路由到对应 Raft 组的
 * {@link ReplicatedStorageEngine}；非 Leader 写请求转为 Redis 兼容的 {@code MOVED}/{@code TRYAGAIN}。
 */
public final class MultiRaftStorageEngine implements StorageEngine, AtomicStringOps {

    private final List<String> groupIds;
    private final RaftGroupManager manager;
    private final Map<String, InetSocketAddress> respAddresses;

    public MultiRaftStorageEngine(List<String> groupIds,
                                  RaftGroupManager manager,
                                  Map<String, InetSocketAddress> respAddresses) {
        if (groupIds == null || groupIds.isEmpty()) {
            throw new IllegalArgumentException("groupIds must not be empty");
        }
        this.groupIds = List.copyOf(groupIds);
        this.manager = Objects.requireNonNull(manager, "manager");
        this.respAddresses = respAddresses == null ? Map.of() : Map.copyOf(respAddresses);
    }

    public String groupFor(byte[] key) {
        int slot = HashSlotRouter.slot(key);
        return groupIds.get(slot % groupIds.size());
    }

    private ReplicatedStorageEngine engineFor(byte[] key) {
        String groupId = groupFor(key);
        return (ReplicatedStorageEngine) manager.storageFor(groupId);
    }

    private <T> T executeOnGroup(byte[] key, Supplier<T> action) {
        String groupId = groupFor(key);
        int slot = HashSlotRouter.slot(key);
        try {
            return action.get();
        } catch (IllegalStateException ex) {
            if (isNotLeader(ex)) {
                throw redirectFor(groupId, slot, ex);
            }
            throw ex;
        } catch (java.util.concurrent.CompletionException ex) {
            if (ex.getCause() instanceof IllegalStateException ise && isNotLeader(ise)) {
                throw redirectFor(groupId, slot, ise);
            }
            throw ex;
        }
    }

    private static boolean isNotLeader(Throwable ex) {
        String msg = ex.getMessage();
        return msg != null && msg.toLowerCase(java.util.Locale.ROOT).contains("not leader");
    }

    private StorageRedirectException redirectFor(String groupId, int slot, Throwable cause) {
        RaftNode raft = manager.raftFor(groupId);
        String leaderId = raft.leaderId();
        if (leaderId != null) {
            InetSocketAddress addr = respAddresses.get(leaderId);
            if (addr != null) {
                return StorageRedirectException.moved(slot, addr.getHostString(), addr.getPort(), cause);
            }
        }
        return StorageRedirectException.tryAgain("group " + groupId + " has no active leader", cause);
    }

    @Override
    public void put(byte[] key, byte[] value) {
        put(key, value, NO_TTL);
    }

    @Override
    public void put(byte[] key, byte[] value, long ttlMillis) {
        executeOnGroup(key, () -> {
            engineFor(key).put(key, value, ttlMillis);
            return null;
        });
    }

    @Override
    public byte[] get(byte[] key) {
        return engineFor(key).get(key);
    }

    @Override
    public boolean delete(byte[] key) {
        return executeOnGroup(key, () -> engineFor(key).delete(key));
    }

    @Override
    public boolean exists(byte[] key) {
        return engineFor(key).exists(key);
    }

    @Override
    public StorageIterator iterator() {
        List<KeyValueEntry> merged = new ArrayList<>();
        for (String groupId : groupIds) {
            try (StorageIterator it = manager.storageFor(groupId).iterator()) {
                while (it.hasNext()) {
                    merged.add(it.next());
                }
            }
        }
        merged.sort((a, b) -> Arrays.compareUnsigned(a.key(), b.key()));
        return new ListStorageIterator(merged);
    }

    @Override
    public long size() {
        long total = 0;
        for (String groupId : groupIds) {
            total += manager.storageFor(groupId).size();
        }
        return total;
    }

    @Override
    public long increment(byte[] key, long delta) {
        return executeOnGroup(key, () -> engineFor(key).increment(key, delta));
    }

    @Override
    public int append(byte[] key, byte[] value) {
        return executeOnGroup(key, () -> engineFor(key).append(key, value));
    }

    @Override
    public byte[] getSet(byte[] key, byte[] value) {
        return executeOnGroup(key, () -> engineFor(key).getSet(key, value));
    }

    @Override
    public byte[] getAndSetPreservingTtl(byte[] key, byte[] value) {
        return executeOnGroup(key, () -> engineFor(key).getAndSetPreservingTtl(key, value));
    }

    @Override
    public byte[] getDelete(byte[] key) {
        return executeOnGroup(key, () -> engineFor(key).getDelete(key));
    }

    @Override
    public boolean putIfAbsent(byte[] key, byte[] value) {
        return executeOnGroup(key, () -> engineFor(key).putIfAbsent(key, value));
    }

    @Override
    public long ttlMillis(byte[] key) {
        return engineFor(key).ttlMillis(key);
    }

    @Override
    public boolean persist(byte[] key) {
        return executeOnGroup(key, () -> engineFor(key).persist(key));
    }

    @Override
    public boolean expireAt(byte[] key, long expireAtMillis) {
        return executeOnGroup(key, () -> engineFor(key).expireAt(key, expireAtMillis));
    }

    @Override
    public byte[] update(byte[] key, UnaryOperator<byte[]> transform) {
        return executeOnGroup(key, () -> engineFor(key).update(key, transform));
    }

    @Override
    public long versionOf(byte[] key) {
        return engineFor(key).versionOf(key);
    }

    /** 供 INFO cluster section 输出实时 Raft 组拓扑与主从状态。 */
    public String clusterInfoSection() {
        StringBuilder sb = new StringBuilder("# Cluster\r\n");
        sb.append("cluster_enabled:1\r\n");
        sb.append("cluster_groups:").append(groupIds.size()).append("\r\n");
        for (String groupId : groupIds) {
            RaftNode raft = manager.raftFor(groupId);
            String leader = raft.leaderId() == null ? "none" : raft.leaderId();
            sb.append("group_").append(groupId)
                    .append(":role=").append(raft.state())
                    .append(",term=").append(raft.currentTerm())
                    .append(",leader=").append(leader)
                    .append("\r\n");
        }
        return sb.toString();
    }

    private static final class ListStorageIterator implements StorageIterator {
        private final List<KeyValueEntry> items;
        private int cursor;

        ListStorageIterator(List<KeyValueEntry> items) {
            this.items = items;
        }

        @Override
        public boolean hasNext() {
            return cursor < items.size();
        }

        @Override
        public KeyValueEntry next() {
            return items.get(cursor++);
        }

        @Override
        public void close() {
            // 快照列表无需额外释放
        }
    }
}
