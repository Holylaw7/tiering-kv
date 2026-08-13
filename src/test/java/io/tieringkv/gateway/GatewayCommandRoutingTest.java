package io.tieringkv.gateway;

import io.tieringkv.cluster.gateway.RedisClusterGateway;
import io.tieringkv.cluster.sharding.HashSlotRouter;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.StorageEngine;
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.Arguments;
import org.junit.jupiter.params.provider.MethodSource;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import static org.assertj.core.api.Assertions.assertThat;

/** 网关命令路由与 CROSSSLOT（ADR-0274）。 */
class GatewayCommandRoutingTest {

    private final StorageEngine n1 = MemTable.create();
    private final StorageEngine n2 = MemTable.create();
    private final RedisClusterGateway gateway = new RedisClusterGateway(
            2,
            Map.of(0, "n1", 1, "n2"),
            Map.of("n1", n1, "n2", n2),
            Map.of("n1", new InetSocketAddress("127.0.0.1", 7001),
                    "n2", new InetSocketAddress("127.0.0.1", 7002)),
            "n1");

    private static boolean localSlot(byte[] key) {
        return HashSlotRouter.slot(key) < 8192;
    }

    private static byte[] localKey() {
        for (int i = 0; i < 200; i++) {
            byte[] key = ("local-key-" + i).getBytes(
                    StandardCharsets.UTF_8);
            if (localSlot(key)) {
                return key;
            }
        }
        throw new AssertionError("no local key found");
    }

    private static byte[] remoteKey() {
        for (int i = 0; i < 200; i++) {
            byte[] key = ("remote-key-" + i).getBytes(
                    StandardCharsets.UTF_8);
            if (!localSlot(key)) {
                return key;
            }
        }
        throw new AssertionError("no remote key found");
    }

    private static byte[][] localKeys(int count) {
        int targetSlot = -1;
        List<byte[]> keys = new ArrayList<>();
        for (int i = 0; i < 1_000_000
                && keys.size() < count; i++) {
            byte[] key = ("same-slot-" + i).getBytes(
                    StandardCharsets.UTF_8);
            int slot = HashSlotRouter.slot(key);
            if (targetSlot == -1) {
                if (localSlot(key)) {
                    targetSlot = slot;
                    keys.add(key);
                }
            } else if (slot == targetSlot) {
                keys.add(key);
            }
        }
        if (keys.size() < count) {
            throw new AssertionError("no same-slot local keys");
        }
        return keys.toArray(byte[][]::new);
    }

    @Test
    void localIncrRouted() {
        RespValue result = gateway.execute("incr",
                List.of(localKey()));
        assertThat(result).isInstanceOf(RespInteger.class);
        assertThat(((RespInteger) result).value()).isEqualTo(1);
    }

    @Test
    void remoteIncrReturnsMoved() {
        RespValue result = gateway.execute("incr",
                List.of(remoteKey()));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .startsWith("MOVED ");
    }

    @Test
    void crossslotMsetRejected() {
        RespValue result = gateway.execute("mset",
                List.of(localKey(), "1".getBytes(
                        StandardCharsets.UTF_8),
                        remoteKey(), "2".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .startsWith("CROSSSLOT");
    }

    @Test
    void sameSlotMsetOk() {
        byte[][] keys = localKeys(2);
        RespValue result = gateway.execute("mset",
                List.of(keys[0], "1".getBytes(
                        StandardCharsets.UTF_8),
                        keys[1], "2".getBytes(
                        StandardCharsets.UTF_8)));
        assertThat(result).isEqualTo(new RespSimpleString("OK"));
    }

    @Test
    void sameSlotDelMultiKey() {
        byte[][] keys = localKeys(3);
        RespValue result = gateway.execute("del",
                List.of(keys[0], keys[1], keys[2]));
        assertThat(result).isInstanceOf(RespInteger.class);
    }

    @Test
    void sameSlotExistsMultiKey() {
        byte[][] keys = localKeys(2);
        RespValue result = gateway.execute("exists",
                List.of(keys[0], keys[1]));
        assertThat(result).isInstanceOf(RespInteger.class);
    }

    @Test
    void nodeLocalScanAndDbsize() {
        gateway.execute("set", List.of(localKey(), "1".getBytes(
                StandardCharsets.UTF_8)));
        assertThat(gateway.execute("scan",
                List.of("0".getBytes(StandardCharsets.UTF_8))))
                .isInstanceOf(RespArray.class);
        assertThat(gateway.execute("dbsize", List.of()))
                .isInstanceOf(RespInteger.class);
    }

    @Test
    void unknownCommandError() {
        RespValue result = gateway.execute("bogus", List.of());
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .contains("unknown command");
    }

    @ParameterizedTest(name = "single-key {0}")
    @MethodSource("singleKeyCommands")
    void singleKeyCommandsRouteLocally(String command,
                                       Object[] args) {
        Object[] routed = new Object[args.length];
        routed[0] = localKey();
        System.arraycopy(args, 1, routed, 1, args.length - 1);
        RespValue result = gateway.execute(command,
                toBytes(command, routed));
        assertThat(result).isNotInstanceOf(RespError.class)
                .isNotNull();
    }

    @ParameterizedTest(name = "moved {0}")
    @MethodSource("movedCommands")
    void remoteKeysReturnMoved(String command, Object[] args) {
        Object[] routed = new Object[args.length];
        routed[0] = remoteKey();
        System.arraycopy(args, 1, routed, 1, args.length - 1);
        RespValue result = gateway.execute(command,
                toBytes(command, routed));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .startsWith("MOVED ");
    }

    @ParameterizedTest(name = "crossslot {0}")
    @MethodSource("crossSlotCommands")
    void crossSlotMultiKeyRejected(String command, Object[] args) {
        Object[] routed = new Object[args.length];
        routed[0] = localKey();
        routed[1] = remoteKey();
        System.arraycopy(args, 2, routed, 2, args.length - 2);
        RespValue result = gateway.execute(command,
                toBytes(command, routed));
        assertThat(result).isInstanceOf(RespError.class);
        assertThat(((RespError) result).message())
                .startsWith("CROSSSLOT");
    }

    static Stream<Arguments> singleKeyCommands() {
        return Stream.of(
                Arguments.of("incr", new Object[]{"key"}),
                Arguments.of("decr", new Object[]{"key"}),
                Arguments.of("append", new Object[]{"key", "x"}),
                Arguments.of("strlen", new Object[]{"key"}),
                Arguments.of("getset", new Object[]{"key", "v"}),
                Arguments.of("setnx", new Object[]{"key", "v"}),
                Arguments.of("setex", new Object[]{"key", "10", "v"}),
                Arguments.of("psetex", new Object[]{"key", "100",
                        "v"}),
                Arguments.of("getdel", new Object[]{"key"}),
                Arguments.of("getrange", new Object[]{"key", "0",
                        "1"}),
                Arguments.of("setrange", new Object[]{"key", "0",
                        "x"}),
                Arguments.of("ttl", new Object[]{"key"}),
                Arguments.of("pttl", new Object[]{"key"}),
                Arguments.of("expire", new Object[]{"key", "10"}),
                Arguments.of("pexpire", new Object[]{"key", "100"}),
                Arguments.of("expireat", new Object[]{"key",
                        (System.currentTimeMillis() / 1000) + 100}),
                Arguments.of("pexpireat", new Object[]{"key",
                        System.currentTimeMillis() + 100_000}),
                Arguments.of("persist", new Object[]{"key"}),
                Arguments.of("type", new Object[]{"key"}));
    }

    static Stream<Arguments> movedCommands() {
        return Stream.of(
                Arguments.of("incr", new Object[]{"key"}),
                Arguments.of("decr", new Object[]{"key"}),
                Arguments.of("append", new Object[]{"key", "x"}),
                Arguments.of("strlen", new Object[]{"key"}),
                Arguments.of("getset", new Object[]{"key", "v"}),
                Arguments.of("setnx", new Object[]{"key", "v"}),
                Arguments.of("setex", new Object[]{"key", "10", "v"}),
                Arguments.of("psetex", new Object[]{"key", "100",
                        "v"}),
                Arguments.of("getdel", new Object[]{"key"}),
                Arguments.of("getrange", new Object[]{"key", "0",
                        "1"}),
                Arguments.of("setrange", new Object[]{"key", "0",
                        "x"}),
                Arguments.of("ttl", new Object[]{"key"}),
                Arguments.of("pttl", new Object[]{"key"}),
                Arguments.of("expire", new Object[]{"key", "10"}),
                Arguments.of("type", new Object[]{"key"}));
    }

    static Stream<Arguments> crossSlotCommands() {
        return Stream.of(
                Arguments.of("mget", new Object[]{"key", "other"}),
                Arguments.of("mset", new Object[]{"key", "1",
                        "other", "2"}),
                Arguments.of("msetnx", new Object[]{"key", "1",
                        "other", "2"}),
                Arguments.of("del", new Object[]{"key", "other"}),
                Arguments.of("exists", new Object[]{"key", "other"}));
    }

    private static List<byte[]> toBytes(String command,
                                        Object[] args) {
        List<byte[]> result = new ArrayList<>();
        for (Object arg : args) {
            if (arg instanceof byte[] bytes) {
                result.add(bytes);
            } else if (arg instanceof Long value) {
                result.add(Long.toString(value).getBytes(
                        StandardCharsets.UTF_8));
            } else {
                result.add(String.valueOf(arg).getBytes(
                        StandardCharsets.UTF_8));
            }
        }
        return result;
    }
}
