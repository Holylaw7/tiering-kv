package io.tieringkv.cluster.gateway;

import io.tieringkv.cluster.routing.RoutingTableEntry;
import io.tieringkv.cluster.routing.UnifiedRouter;
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
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * 统一路由网关（ADR-0066/0068）：key→slot→region→node；
 * MOVED / ASK / TRYAGAIN 语义统一；CLUSTER SLOTS / CLUSTER NODES。
 */
public final class UnifiedClusterGateway {

    private final UnifiedRouter router;
    private final Map<String, StorageEngine> storages;
    private final Map<String, InetSocketAddress> addresses;
    private final String localNode;

    public UnifiedClusterGateway(UnifiedRouter router,
                                 Map<String, StorageEngine> storages,
                                 Map<String, InetSocketAddress> addresses,
                                 String localNode) {
        this.router = router;
        this.storages = Map.copyOf(storages);
        this.addresses = Map.copyOf(addresses);
        this.localNode = localNode;
    }

    public RespValue execute(String name, List<byte[]> args) {
        return executeWithAsking(name, args, false);
    }

    /**
     * ASK 迁移语义（TD-038）：asking=true 时迁移中 slot 允许本节点
     * 读写（Redis ASKING 单命令语义由 handler 消费）。
     */
    public RespValue executeWithAsking(String name, List<byte[]> args,
                                       boolean asking) {
        switch (name.toLowerCase(Locale.ROOT)) {
            case "asking" -> {
                return new RespSimpleString("OK");
            }
            case "get" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name);
                }
                return localGet(args.get(0), asking);
            }
            case "set" -> {
                if (args.size() < 2) {
                    return RespError.wrongArity(name);
                }
                return localSet(args, asking);
            }
            case "del" -> {
                if (args.size() != 1) {
                    return RespError.wrongArity(name);
                }
                return localDel(args.get(0), asking);
            }
            case "mget" -> {
                if (args.isEmpty()) {
                    return RespError.wrongArity(name);
                }
                return localMget(args, asking);
            }
            case "mset" -> {
                if (args.size() < 2 || args.size() % 2 != 0) {
                    return RespError.wrongArity(name);
                }
                return localMset(args, asking);
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
                return switch (sub) {
                    case "slots" -> clusterSlots();
                    case "nodes" -> clusterNodes();
                    default -> new RespError(
                            "ERR unknown CLUSTER subcommand '" + sub + "'");
                };
            }
            default -> {
                return RespError.unknownCommand(name);
            }
        }
    }

    /** key → 路由条目；迁移中 → ASK；leader 缺失 → TRYAGAIN；远端 → MOVED。 */
    public RouteResult route(byte[] key) {
        return route(key, false);
    }

    public RouteResult route(byte[] key, boolean asking) {
        int slot = HashSlotRouter.slot(key);
        RoutingTableEntry entry = router.routeSlot(slot);
        if (entry.migrating()) {
            if (asking) {
                return RouteResult.local(entry);
            }
            String target = entry.leader();
            if (target == null) {
                return RouteResult.redirect("TRYAGAIN", slot, null);
            }
            return RouteResult.redirect("ASK", slot, addressOf(target));
        }
        if (entry.leader() == null) {
            return RouteResult.redirect("TRYAGAIN", slot, null);
        }
        if (!localNode.equals(entry.leader())) {
            return RouteResult.redirect("MOVED", slot, addressOf(entry.leader()));
        }
        return RouteResult.local(entry);
    }

    private RespValue localGet(byte[] key, boolean asking) {
        RouteResult result = route(key, asking);
        if (!result.local()) {
            return result.error();
        }
        byte[] value = storages.get(localNode).get(key);
        return value == null ? RespNull.BULK_STRING : new RespBulkString(value);
    }

    private RespValue localSet(List<byte[]> args, boolean asking) {
        RouteResult result = route(args.get(0), asking);
        if (!result.local()) {
            return result.error();
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
        storages.get(localNode).put(args.get(0), args.get(1), ttl);
        return new RespSimpleString("OK");
    }

    private RespValue localDel(byte[] key, boolean asking) {
        RouteResult result = route(key, asking);
        if (!result.local()) {
            return result.error();
        }
        return new RespInteger(storages.get(localNode).delete(key) ? 1 : 0);
    }

    private RespValue localMget(List<byte[]> args, boolean asking) {
        List<RespValue> values = new ArrayList<>(args.size());
        for (byte[] key : args) {
            RouteResult result = route(key, asking);
            if (!result.local()) {
                return result.error();
            }
            byte[] value = storages.get(localNode).get(key);
            values.add(value == null ? RespNull.BULK_STRING
                    : new RespBulkString(value));
        }
        return new RespArray(values);
    }

    private RespValue localMset(List<byte[]> args, boolean asking) {
        for (int i = 0; i < args.size(); i += 2) {
            RouteResult result = route(args.get(i), asking);
            if (!result.local()) {
                return result.error();
            }
        }
        for (int i = 0; i < args.size(); i += 2) {
            storages.get(localNode).put(args.get(i), args.get(i + 1));
        }
        return new RespSimpleString("OK");
    }

    private RespValue clusterSlots() {
        List<RespValue> entries = new ArrayList<>();
        int start = 0;
        RoutingTableEntry current = router.routeSlot(0);
        for (int slot = 1; slot <= HashSlotRouter.SLOT_COUNT; slot++) {
            RoutingTableEntry next = slot < HashSlotRouter.SLOT_COUNT
                    ? router.routeSlot(slot) : null;
            if (next == null || !next.regionId().equals(current.regionId())) {
                entries.add(slotEntry(start, slot - 1, current));
                if (next != null) {
                    start = slot;
                    current = next;
                }
            }
        }
        return new RespArray(entries);
    }

    private RespValue slotEntry(int start, int end, RoutingTableEntry entry) {
        InetSocketAddress address = addressOf(entry.leader());
        RespArray node = new RespArray(List.of(
                new RespBulkString(address.getHostString()
                        .getBytes(StandardCharsets.UTF_8)),
                new RespInteger(address.getPort()),
                new RespBulkString(entry.leader().getBytes(StandardCharsets.UTF_8))));
        return new RespArray(List.of(
                new RespInteger(start), new RespInteger(end), node));
    }

    private RespValue clusterNodes() {
        Map<String, RoutingTableEntry> byLeader = new LinkedHashMap<>();
        for (int slot = 0; slot < HashSlotRouter.SLOT_COUNT; slot++) {
            RoutingTableEntry entry = router.routeSlot(slot);
            byLeader.putIfAbsent(entry.leader() + "|" + entry.regionId().id(), entry);
        }
        StringBuilder builder = new StringBuilder();
        for (RoutingTableEntry entry : byLeader.values()) {
            String leader = entry.leader();
            InetSocketAddress address = addressOf(leader);
            String flags = localNode.equals(leader) ? "myself,leader" : "leader";
            builder.append(leader).append(' ').append(address.getHostString())
                    .append(':').append(address.getPort()).append(' ')
                    .append(flags).append(' ')
                    .append(entry.slotStart()).append('-')
                    .append(entry.slotEnd()).append('\n');
        }
        return new RespBulkString(builder.toString()
                .getBytes(StandardCharsets.UTF_8));
    }

    private InetSocketAddress addressOf(String nodeId) {
        InetSocketAddress address = addresses.get(nodeId);
        if (address == null) {
            throw new IllegalStateException("no address for node " + nodeId);
        }
        return address;
    }

    public record RouteResult(boolean local, RoutingTableEntry entry,
                              String redirectKind, int slot,
                              InetSocketAddress target) {

        static RouteResult local(RoutingTableEntry entry) {
            return new RouteResult(true, entry, null, 0, null);
        }

        static RouteResult redirect(String kind, int slot,
                                    InetSocketAddress target) {
            return new RouteResult(false, null, kind, slot, target);
        }

        RespValue error() {
            if (target == null) {
                return new RespError(redirectKind);
            }
            return new RespError(redirectKind + " " + slot + " "
                    + target.getHostString() + ":" + target.getPort());
        }
    }
}
