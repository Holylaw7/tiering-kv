package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.cluster.metrics.GatewayMetricsRegistry;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
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
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

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
    private final AutoTransactionExecutor autoTxn;
    private final TransactionCommandHandler txnHandler;
    private final CommandRegistry commandRegistry;

    public RedisClusterGateway(int shardCount,
                               Map<Integer, String> shardLeaders,
                               Map<String, StorageEngine> storages,
                               Map<String, InetSocketAddress> addresses,
                               String localNode) {
        this(shardCount, shardLeaders, storages, addresses, localNode, null, null);
    }

    public RedisClusterGateway(int shardCount,
                               Map<Integer, String> shardLeaders,
                               Map<String, StorageEngine> storages,
                               Map<String, InetSocketAddress> addresses,
                               String localNode,
                               AutoTransactionExecutor autoTxn,
                               GatewayMetricsRegistry metrics) {
        this.shardCount = shardCount;
        this.shardLeaders = Map.copyOf(shardLeaders);
        this.storages = Map.copyOf(storages);
        this.addresses = Map.copyOf(addresses);
        this.localNode = localNode;
        this.autoTxn = autoTxn;
        this.txnHandler = autoTxn == null ? null
                : new TransactionCommandHandler(autoTxn, metrics == null
                ? new GatewayMetricsRegistry() : metrics);
        this.commandRegistry = CommandRegistry.createDefault();
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
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return routeMultiKey("del", args, 1);
            }
            case "mget" -> {
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return routeMultiKey("mget", args, 1);
            }
            case "mset" -> {
                if (args.size() < 2 || args.size() % 2 != 0) {
                    return RespError.wrongArity(name);
                }
                return routeMultiKey("mset", args, 2);
            }
            case "msetnx" -> {
                if (args.size() < 2 || args.size() % 2 != 0) {
                    return RespError.wrongArity(name);
                }
                return routeMultiKey("msetnx", args, 2);
            }
            case "exists" -> {
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return routeMultiKey("exists", args, 1);
            }
            case "incr", "decr", "incrby", "decrby", "append",
                    "strlen", "getset", "setnx", "setex", "psetex",
                    "getdel", "getrange", "setrange", "ttl", "pttl",
                    "expire", "pexpire", "expireat", "pexpireat",
                    "persist", "type" -> {
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return routeSingleKey(name, args);
            }
            case "scan", "dbsize", "flushdb", "flushall", "config",
                    "client", "command" -> {
                return routeLocal(name, args);
            }
            case "info" -> {
                String info = "# Server\r\ngateway:tiering-kv\r\n"
                        + "cluster_enabled:1\r\n";
                if (txnHandler != null) {
                    info += txnHandler.infoSections();
                }
                return new RespBulkString(info.getBytes(StandardCharsets.UTF_8));
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

    /** 单键命令：本地执行，否则 MOVED。 */
    private RespValue routeSingleKey(String name,
                                     List<byte[]> args) {
        byte[] key = args.get(0);
        if (!isLocal(key)) {
            return moved(key);
        }
        return commandEngine(storageFor(key))
                .execute(new RespCommand(name, args));
    }

    /** 多键命令：全部同槽 + 本地，否则 CROSSSLOT / MOVED。 */
    private RespValue routeMultiKey(String name,
                                    List<byte[]> args, int step) {
        if (txnHandler != null) {
            // 事务网关：保留逐键 MOVED 语义，跨槽原子性由事务协调器保证
            for (int i = 0; i < args.size(); i += step) {
                if (!isLocal(args.get(i))) {
                    return moved(args.get(i));
                }
            }
            return switch (name) {
                case "mget" -> txnHandler.mget(args);
                case "mset" -> txnHandler.mset(args);
                case "del" -> transactionalDel(args);
                default -> commandEngine(storageFor(args.get(0)))
                        .execute(new RespCommand(name, args));
            };
        }
        Set<Integer> slots = new HashSet<>();
        for (int i = 0; i < args.size(); i += step) {
            slots.add(HashSlotRouter.slot(args.get(i)));
        }
        if (slots.size() > 1) {
            return new RespError("CROSSSLOT Keys in request don't "
                    + "hash to the same slot");
        }
        for (int i = 0; i < args.size(); i += step) {
            if (!isLocal(args.get(i))) {
                return moved(args.get(i));
            }
        }
        StorageEngine storage = storageFor(args.get(0));
        return commandEngine(storage)
                .execute(new RespCommand(name, args));
    }

    /** 事务网关多键 DEL：逐键删除并计数（重复键只计一次）。 */
    private RespValue transactionalDel(List<byte[]> args) {
        long removed = 0;
        List<byte[]> seen = new ArrayList<>();
        for (byte[] key : args) {
            boolean duplicate = seen.stream().anyMatch(
                    other -> java.util.Arrays.equals(other, key));
            if (duplicate) {
                continue;
            }
            RespValue result = txnHandler.del(key);
            if (result instanceof RespInteger integer
                    && integer.value() > 0) {
                removed++;
            }
            seen.add(key);
        }
        return new RespInteger(removed);
    }

    /** 节点本地命令：本地存储执行（真实 Redis Cluster 语义）。 */
    private RespValue routeLocal(String name, List<byte[]> args) {
        StorageEngine storage = storages.get(localNode);
        if (storage == null) {
            return new RespError("ERR no local storage");
        }
        return commandEngine(storage)
                .execute(new RespCommand(name, args));
    }

    private CommandEngine commandEngine(StorageEngine storage) {
        return new CommandEngine(commandRegistry, storage);
    }

    private RespValue localGet(byte[] key) {
        if (!isLocal(key)) {
            return moved(key);
        }
        if (txnHandler != null) {
            return txnHandler.get(key);
        }
        byte[] value = storageFor(key).get(key);
        return value == null ? RespNull.BULK_STRING : new RespBulkString(value);
    }

    private RespValue localSet(List<byte[]> args) {
        byte[] key = args.get(0);
        if (!isLocal(key)) {
            return moved(key);
        }
        if (txnHandler != null) {
            if (args.size() >= 4) {
                return new RespError(
                        "ERR EX/PX not supported with transactional gateway");
            }
            return txnHandler.set(key, args.get(1));
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
        if (txnHandler != null) {
            return txnHandler.del(key);
        }
        return new RespInteger(storageFor(key).delete(key) ? 1 : 0);
    }

    private RespValue localMget(List<byte[]> args) {
        if (txnHandler != null) {
            for (byte[] key : args) {
                if (!isLocal(key)) {
                    return moved(key);
                }
            }
            return txnHandler.mget(args);
        }
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
        if (txnHandler != null) {
            return txnHandler.mset(args);
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
