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
import io.tieringkv.storage.memory.MemTable;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** Redis Cluster 网关（Phase 17）：命令执行 + MOVED + CLUSTER SLOTS。 */
class RedisGatewayTest {

    private RedisClusterGateway gateway;
    private MemTable n1;
    private MemTable n2;
    private byte[] localKey;
    private byte[] remoteKey;

    @BeforeEach
    void setUp() {
        Map<Integer, String> leaders = Map.of(0, "n1", 1, "n2");
        n1 = MemTable.create();
        n2 = MemTable.create();
        Map<String, StorageEngine> storages = Map.of("n1", n1, "n2", n2);
        Map<String, InetSocketAddress> addresses = Map.of(
                "n1", new InetSocketAddress("127.0.0.1", 7001),
                "n2", new InetSocketAddress("127.0.0.1", 7002));
        gateway = new RedisClusterGateway(2, leaders,
                storages, addresses, "n1");
        localKey = keyInShard(0);
        remoteKey = keyInShard(1);
    }

    @Test
    void getLocalReturnsValue() {
        n1.put(localKey, bytes("v1"));
        RespValue response = gateway.execute("get", List.of(localKey));
        assertThat(response).isInstanceOf(RespBulkString.class);
        assertThat(((RespBulkString) response).bytes()).isEqualTo(bytes("v1"));
    }

    @Test
    void getMissingReturnsNull() {
        RespValue response = gateway.execute("get", List.of(localKey));
        assertThat(response).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void setThenGet() {
        gateway.execute("set", List.of(localKey, bytes("value")));
        assertThat(n1.get(localKey)).isEqualTo(bytes("value"));
    }

    @Test
    void setWithExSeconds() {
        gateway.execute("set", List.of(localKey, bytes("v"),
                bytes("EX"), bytes("10")));
        assertThat(n1.getEntry(localKey).expireTimestamp()).isGreaterThan(0);
    }

    @Test
    void setWithPxMillis() {
        gateway.execute("set", List.of(localKey, bytes("v"),
                bytes("PX"), bytes("5000")));
        assertThat(n1.getEntry(localKey).expireTimestamp()).isGreaterThan(0);
    }

    @Test
    void delReturnsOne() {
        n1.put(localKey, bytes("v"));
        RespValue response = gateway.execute("del", List.of(localKey));
        assertThat(((RespInteger) response).value()).isEqualTo(1);
        assertThat(n1.get(localKey)).isNull();
    }

    @Test
    void delMissingReturnsZero() {
        RespValue response = gateway.execute("del", List.of(localKey));
        assertThat(((RespInteger) response).value()).isZero();
    }

    @Test
    void mgetLocalValues() {
        n1.put(localKey, bytes("a"));
        byte[] second = keyInSlot(localKey);
        n1.put(second, bytes("b"));
        RespValue response = gateway.execute("mget",
                List.of(localKey, second));
        RespArray array = (RespArray) response;
        assertThat(array.values()).hasSize(2);
        assertThat(((RespBulkString) array.values().get(0)).bytes())
                .isEqualTo(bytes("a"));
    }

    @Test
    void mgetMissingReturnsNulls() {
        byte[] missing = keyInSlot(localKey);
        RespValue response = gateway.execute("mget",
                List.of(localKey, missing));
        RespArray array = (RespArray) response;
        assertThat(array.values().get(0)).isEqualTo(RespNull.BULK_STRING);
    }

    @Test
    void msetLocal() {
        gateway.execute("mset", List.of(localKey, bytes("a")));
        assertThat(n1.get(localKey)).isEqualTo(bytes("a"));
    }

    @Test
    void getRemoteReturnsMoved() {
        RespValue response = gateway.execute("get", List.of(remoteKey));
        assertThat(response).isInstanceOf(RespError.class);
        assertThat(((RespError) response).message()).startsWith("MOVED ");
    }

    @Test
    void setRemoteReturnsMoved() {
        RespValue response = gateway.execute("set",
                List.of(remoteKey, bytes("v")));
        assertThat(((RespError) response).message()).startsWith("MOVED ");
        assertThat(n2.get(remoteKey)).isNull();
    }

    @Test
    void delRemoteReturnsMoved() {
        RespValue response = gateway.execute("del", List.of(remoteKey));
        assertThat(((RespError) response).message()).startsWith("MOVED ");
    }

    @Test
    void movedContainsSlotAndAddress() {
        RespValue response = gateway.execute("get", List.of(remoteKey));
        String message = ((RespError) response).message();
        int slot = HashSlotRouter.slot(remoteKey);
        assertThat(message).contains("MOVED " + slot + " 127.0.0.1:7002");
    }

    @Test
    void mgetMixedReturnsCrossslot() {
        RespValue response = gateway.execute("mget",
                List.of(localKey, remoteKey));
        assertThat(((RespError) response).message())
                .startsWith("CROSSSLOT");
    }

    @Test
    void msetMixedReturnsCrossslot() {
        RespValue response = gateway.execute("mset",
                List.of(localKey, bytes("a"), remoteKey, bytes("b")));
        assertThat(((RespError) response).message())
                .startsWith("CROSSSLOT");
        assertThat(n1.get(localKey)).isNull();
    }

    @Test
    void sameSlotRemoteMultiKeyReturnsMoved() {
        byte[] remoteSameSlot = keyInSlot(remoteKey);
        RespValue response = gateway.execute("mget",
                List.of(remoteKey, remoteSameSlot));
        assertThat(((RespError) response).message())
                .startsWith("MOVED ");
    }

    @Test
    void clusterSlotsListsRanges() {
        RespValue response = gateway.execute("cluster",
                List.of(bytes("SLOTS")));
        RespArray array = (RespArray) response;
        assertThat(array.values()).hasSize(2);
        RespArray first = (RespArray) array.values().get(0);
        assertThat(((RespInteger) first.values().get(0)).value()).isZero();
        RespArray node = (RespArray) first.values().get(2);
        assertThat(((RespBulkString) node.values().get(2)).bytes())
                .isEqualTo(bytes("n1"));
    }

    @Test
    void clusterSlotsContainsBothNodeIds() {
        RespValue response = gateway.execute("cluster",
                List.of(bytes("slots")));
        String text = response.toString();
        assertThat(text).contains("n1").contains("n2");
    }

    @Test
    void infoReturnsClusterEnabled() {
        RespValue response = gateway.execute("info", List.of());
        String text = new String(((RespBulkString) response).bytes(),
                StandardCharsets.UTF_8);
        assertThat(text).contains("cluster_enabled:1");
    }

    @Test
    void unknownCommandError() {
        RespValue response = gateway.execute("nosuch", List.of());
        assertThat(((RespError) response).message()).contains("unknown command");
    }

    @Test
    void wrongArityError() {
        RespValue response = gateway.execute("get", List.of());
        assertThat(((RespError) response).message())
                .contains("wrong number of arguments");
    }

    @Test
    void isLocalRouting() {
        assertThat(gateway.isLocal(localKey)).isTrue();
        assertThat(gateway.isLocal(remoteKey)).isFalse();
    }

    @Test
    void movedTargetFormat() {
        assertThat(gateway.movedTarget(remoteKey))
                .matches("\\d+ 127\\.0\\.0\\.1:7002");
    }

    @Test
    void setReturnsOk() {
        RespValue response = gateway.execute("set",
                List.of(localKey, bytes("v")));
        assertThat(response).isEqualTo(new RespSimpleString("OK"));
    }

    private static byte[] keyInShard(int shard) {
        return keyInShard(shard, 0);
    }

    /** 与 anchor 同 slot 的键（跨槽校验用）。 */
    private byte[] keyInSlot(byte[] anchor) {
        int target = HashSlotRouter.slot(anchor);
        for (int i = 0; i < 200_000; i++) {
            byte[] candidate = ("same-slot-" + i)
                    .getBytes(StandardCharsets.UTF_8);
            if (HashSlotRouter.slot(candidate) == target) {
                return candidate;
            }
        }
        throw new AssertionError("no same-slot key found");
    }

    private static byte[] keyInShard(int shard, int start) {
        for (int i = start; i < start + 1000; i++) {
            byte[] key = bytes("gw:key:" + i);
            int slot = HashSlotRouter.slot(key);
            if (slot * 2 / HashSlotRouter.SLOT_COUNT == shard) {
                return key;
            }
        }
        throw new IllegalStateException("no key for shard " + shard);
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}
