package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespNull;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Redis Cluster 网关（Phase 17）：GET/SET/DEL/MGET/MSET/INFO/CLUSTER SLOTS；
 * 非本地键返回 MOVED slot host:port（Redis Cluster 兼容）。
 */
public final class RedisClusterGateway {

    private final int shardCount;
    private final Map<Integer, String> shardLeaders;
    private final Map<String, StorageEngine> storages;
    private final Map<String, InetSocketAddress> addresses;
    private final String localNode;

    public RedisClusterGateway(int shardCount,
                               Map<Integer, String> shardLeaders,
                               Map<String, StorageEngine> storages,
                               Map<String, InetSocketAddress> addresses,
                               String localNode) {
        this.shardCount = shardCount;
        this.shardLeaders = Map.copyOf(shardLeaders);
        this.storages = Map.copyOf(storages);
        this.addresses = Map.copyOf(addresses);
        this.localNode = localNode;
    }

    public RespValue execute(String name, List<byte[]> args) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "get" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name);
                }
                return localGet(args.get(0));
            }
            case "set" -> {
                if (args.size() < 2) {
                    return RespError.wrongArity(name);
                }
                return localSet(args);
            }
            case "del" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name);
                }
                return localDel(args.get(0));
            }
            case "mget" -> {
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return localMget(args);
            }
            case "mset" -> {
                if (args.size() < 2 || args.size() % 2 != 0) {
                    return RespError.wrongArity(name);
                }
                return localMset(args);
            }
            case "info" -> {
                return new RespBulkString(
                        ("# Server\r\ngateway:tiering-kv\r\n"
                                + "cluster_enabled:1\r\n")
                                .getBytes(StandardCharsets.UTF_8));
            }
            case "cluster" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name);
                }
                String sub = new String(args.get(0), StandardCharsets.UTF_8)
                        .toLowerCase(Locale.ROOT);
                if ("slots".equals(sub)) {
                    return clusterSlots();
                }
                return new RespError("ERR unknown CLUSTER subcommand '" + sub + "'");
            }
            default -> {
                return RespError.unknownCommand(name);
            }
        }
    }

    public boolean isLocal(byte[] key) {
        return localNode.equals(shardLeaders.get(shardFor(HashSlotRouter.slot(key))));
    }

    public String movedTarget(byte[] key) {
        int slot = HashSlotRouter.slot(key);
        int shard = shardFor(slot);
        String leader = shardLeaders.get(shard);
        InetSocketAddress address = addresses.get(leader);
        return slot + " " + address.getHostString() + ":" + address.getPort();
    }

    private RespValue moved(byte[] key) {
        return new RespError("MOVED " + movedTarget(key));
    }

    private RespValue localGet(byte[] key) {
        if (!isLocal(key)) {
            return moved(key);
        }
        byte[] value = storageFor(key).get(key);
        return value == null ? RespNull.BULK_STRING : new RespBulkString(value);
    }

    private RespValue localSet(List<byte[]> args) {
        byte[] key = args.get(0);
        if (!isLocal(key)) {
            return moved(key);
        }
        long ttl = -1;
        if (args.size() >= 4) {
            String option = new String(args.get(2), StandardCharsets.UTF_8)
                    .toUpperCase(Locale.ROOT);
            if ("EX".equals(option)) {
                ttl = Long.parseLong(new String(args.get(3),
                        StandardCharsets.UTF_8)) * 1000;
            } else if ("PX".equals(option)) {
                ttl = Long.parseLong(new String(args.get(3),
                        StandardCharsets.UTF_8));
            }
        }
        storageFor(key).put(key, args.get(1), ttl);
        return new RespSimpleString("OK");
    }

    private RespValue localDel(byte[] key) {
        if (!isLocal(key)) {
            return moved(key);
        }
        return new RespInteger(storageFor(key).delete(key) ? 1 : 0);
    }

    private RespValue localMget(List<byte[]> args) {
        List<RespValue> values = new ArrayList<>(args.size());
        for (byte[] key : args) {
            if (!isLocal(key)) {
                return moved(key);
            }
            byte[] value = storageFor(key).get(key);
            values.add(value == null ? RespNull.BULK_STRING
                    : new RespBulkString(value));
        }
        return new RespArray(values);
    }

    private RespValue localMset(List<byte[]> args) {
        for (int i = 0; i < args.size(); i += 2) {
            if (!isLocal(args.get(i))) {
                return moved(args.get(i));
            }
        }
        for (int i = 0; i < args.size(); i += 2) {
            storageFor(args.get(i)).put(args.get(i), args.get(i + 1));
        }
        return new RespSimpleString("OK");
    }

    private StorageEngine storageFor(byte[] key) {
        int shard = shardFor(HashSlotRouter.slot(key));
        return storages.get(shardLeaders.get(shard));
    }

    private RespValue clusterSlots() {
        List<RespValue> entries = new ArrayList<>();
        int start = 0;
        for (int shard = 0; shard < shardCount; shard++) {
            int end = (int) ((long) (shard + 1) * HashSlotRouter.SLOT_COUNT
                    / shardCount) - 1;
            entries.add(slotEntry(start, end, shard));
            start = end + 1;
        }
        return new RespArray(entries);
    }

    /** 连续槽位区间：shard s 覆盖 [s*N/n, (s+1)*N/n)。 */
    private int shardFor(int slot) {
        return Math.min(shardCount - 1,
                (int) ((long) slot * shardCount / HashSlotRouter.SLOT_COUNT));
    }

    private RespValue slotEntry(int start, int end, int shard) {
        String leader = shardLeaders.get(shard);
        InetSocketAddress address = addresses.get(leader);
        RespArray node = new RespArray(List.of(
                new RespBulkString(address.getHostString()
                        .getBytes(StandardCharsets.UTF_8)),
                new RespInteger(address.getPort()),
                new RespBulkString(leader.getBytes(StandardCharsets.UTF_8))));
        return new RespArray(List.of(
                new RespInteger(start),
                new RespInteger(end),
                node));
    }
}
