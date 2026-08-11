package io.tieringkv.protocol;

import io.netty.buffer.ByteBuf;
import io.netty.buffer.Unpooled;
import io.netty.channel.embedded.EmbeddedChannel;
import io.tieringkv.cluster.raft.AppendEntriesRequest;
import io.tieringkv.cluster.raft.LogEntry;
import io.tieringkv.cluster.raft.VoteRequest;
import io.tieringkv.cluster.rpc.RaftMessageCodec;
import io.tieringkv.cluster.rpc.RpcMessageType;
import io.tieringkv.transaction.metadata.TxnMetaCodec;
import io.tieringkv.transaction.metadata.TxnMetaCommand;
import io.tieringkv.transaction.rpc.TxnMessages;
import io.tieringkv.transaction.rpc.TxnRpcCodec;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/** 协议兼容性矩阵（ADR-0103）：RESP2 / RPC v1 / 元数据命令 v1。 */
class ProtocolCompatibilityTest {

    @Test
    void frozenVersionConstants() {
        assertThat(ProtocolVersion.RPC_VERSION).isEqualTo(1);
        assertThat(ProtocolVersion.RESP_VERSION).isEqualTo(2);
        assertThat(ProtocolVersion.STORAGE_FORMAT_VERSION).isEqualTo(1);
        assertThat(ProtocolVersion.META_COMMAND_VERSION).isEqualTo(1);
    }

    @ParameterizedTest(name = "command {0}")
    @ValueSource(strings = {"SET", "GET", "DEL", "EXISTS", "PING", "ECHO"})
    void oldClientCommandEncodeDecode(String command) {
        RespValue request = new RespArray(List.of(
                new RespBulkString(command.getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("k".getBytes(StandardCharsets.UTF_8)),
                new RespBulkString("v".getBytes(StandardCharsets.UTF_8))));
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, request);
        RespValue decoded = decodeOnce(buffer);
        assertThat(decoded).isInstanceOf(RespArray.class);
        RespArray array = (RespArray) decoded;
        assertThat(new String(((RespBulkString) array.values().get(0))
                .bytes(), StandardCharsets.UTF_8)).isEqualTo(command);
        buffer.release();
    }

    @ParameterizedTest(name = "value {0}")
    @ValueSource(strings = {"", "hello", "a\r\nb", "中文", "\u0000byte"})
    void oldClientBinarySafeRoundTrip(String value) {
        RespBulkString bulk = new RespBulkString(
                value.getBytes(StandardCharsets.UTF_8));
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, bulk);
        RespValue decoded = decodeOnce(buffer);
        assertThat(new String(((RespBulkString) decoded).bytes(),
                StandardCharsets.UTF_8)).isEqualTo(value);
        buffer.release();
    }

    @ParameterizedTest(name = "n {0}")
    @ValueSource(ints = {0, 1, 3, 10})
    void oldClientPipelineDecode(int count) {
        ByteBuf buffer = Unpooled.buffer();
        for (int i = 0; i < count; i++) {
            RespEncoder.write(buffer, new RespArray(List.of(
                    new RespBulkString("PING".getBytes()))));
        }
        assertThat(decodeAll(buffer)).hasSize(count);
        buffer.release();
    }

    @Test
    void oldClientInlineCommand() {
        ByteBuf buffer = Unpooled.copiedBuffer(
                "PING\r\n".getBytes(StandardCharsets.US_ASCII));
        assertThat(decodeAll(buffer)).hasSize(1);
        buffer.release();
    }

    @ParameterizedTest(name = "wire {0}")
    @ValueSource(strings = {"+OK\r\n", "-ERR boom\r\n", ":42\r\n",
            "$-1\r\n", "$3\r\nfoo\r\n", "*2\r\n$1\r\na\r\n$1\r\nb\r\n"})
    void oldClientWireResponses(String wire) {
        ByteBuf buffer = Unpooled.copiedBuffer(
                wire.getBytes(StandardCharsets.US_ASCII));
        assertThat(decodeAll(buffer)).hasSize(1);
        buffer.release();
    }

    @Test
    void rpcV1VoteRoundTrip() {
        VoteRequest request = new VoteRequest(3, "n1", 5, 4);
        VoteRequest decoded = RaftMessageCodec.decodeVoteRequest(
                RaftMessageCodec.encode(request));
        assertThat(decoded.term()).isEqualTo(3);
        assertThat(decoded.candidateId()).isEqualTo("n1");
        assertThat(decoded.lastLogIndex()).isEqualTo(5);
        assertThat(decoded.lastLogTerm()).isEqualTo(4);
    }

    @ParameterizedTest(name = "term {0}")
    @ValueSource(longs = {0, 1, Long.MAX_VALUE})
    void rpcV1AppendEntriesRoundTrip(long term) {
        AppendEntriesRequest request = new AppendEntriesRequest(term,
                "leader-1", 7, 6, List.of(new LogEntry(term, 7,
                new byte[]{1, 2, 3})), 5);
        AppendEntriesRequest decoded =
                RaftMessageCodec.decodeAppendEntriesRequest(
                        RaftMessageCodec.encode(request));
        assertThat(decoded.term()).isEqualTo(term);
        assertThat(decoded.entries()).hasSize(1);
        assertThat(decoded.entries().get(0).command())
                .isEqualTo(new byte[]{1, 2, 3});
    }

    @Test
    void rpcV1MessageTypeValuesFrozen() {
        assertThat(RpcMessageType.APPEND_ENTRIES.wireValue()).isEqualTo(1);
        assertThat(RpcMessageType.REQUEST_VOTE.wireValue()).isEqualTo(3);
        assertThat(RpcMessageType.TXN_GET_RESPONSE.wireValue()).isEqualTo(27);
        assertThat(RpcMessageType.META_PROPOSE.wireValue()).isEqualTo(28);
        assertThat(RpcMessageType.META_STATUS.wireValue()).isEqualTo(30);
    }

    @ParameterizedTest(name = "mutation {0}")
    @ValueSource(ints = {0, 1, 5, 20})
    void txnRpcV1PrewriteRoundTrip(int mutationCount) {
        TxnMessages.Prewrite request = new TxnMessages.Prewrite(
                "t1", 42, new byte[]{1}, mutations(mutationCount));
        TxnMessages.Prewrite decoded = TxnRpcCodec.decodePrewrite(
                TxnRpcCodec.encodePrewrite(request));
        assertThat(decoded.txnId()).isEqualTo("t1");
        assertThat(decoded.startTS()).isEqualTo(42);
        assertThat(decoded.primary()).isEqualTo(new byte[]{1});
        assertThat(decoded.mutations()).hasSize(mutationCount);
    }

    @ParameterizedTest(name = "mutation {0}")
    @ValueSource(ints = {0, 1, 5, 20})
    void txnRpcV1CommitRoundTrip(int mutationCount) {
        TxnMessages.Commit request = new TxnMessages.Commit(
                "t1", 42, 84, new byte[]{1}, mutations(mutationCount));
        TxnMessages.Commit decoded = TxnRpcCodec.decodeCommit(
                TxnRpcCodec.encodeCommit(request));
        assertThat(decoded.commitTS()).isEqualTo(84);
        assertThat(decoded.mutations()).hasSize(mutationCount);
    }

    @ParameterizedTest(name = "type {0}")
    @ValueSource(strings = {"REGISTER", "PREPARE", "COMMIT", "ROLLBACK",
            "LIFECYCLE"})
    void metaCommandV1RoundTrip(String type) {
        TxnMetaCommand command = switch (type) {
            case "PREPARE" -> TxnMetaCommand.prepare("t1", 84);
            case "COMMIT" -> TxnMetaCommand.commit("t1", 84);
            case "ROLLBACK" -> TxnMetaCommand.rollback("t1");
            case "LIFECYCLE" -> TxnMetaCommand.lifecycle("t1", 1,
                    "ACTIVE", 1000);
            default -> TxnMetaCommand.register("t1", new byte[]{1}, 1,
                    Map.of("r1", List.of()));
        };
        TxnMetaCommand decoded = TxnMetaCodec.decode(
                TxnMetaCodec.encode(command));
        assertThat(decoded.type().name()).isEqualTo(type);
        assertThat(decoded.txnId()).isEqualTo("t1");
    }

    @ParameterizedTest(name = "count {0}")
    @ValueSource(ints = {1, 5, 20, 100})
    void metaCommandV1MutationCountRoundTrip(int count) {
        Map<String, List<TxnMessages.Mutation>> regions = Map.of(
                "r1", mutations(count));
        TxnMetaCommand command = TxnMetaCommand.register(
                "t1", new byte[]{1}, 1, regions);
        TxnMetaCommand decoded = TxnMetaCodec.decode(
                TxnMetaCodec.encode(command));
        assertThat(decoded.regionMutations().get("r1")).hasSize(count);
    }

    @ParameterizedTest(name = "size {0}")
    @ValueSource(ints = {0, 1, 1024, 65536})
    void oldClientLargeValueRoundTrip(int size) {
        byte[] value = new byte[size];
        RespBulkString bulk = new RespBulkString(value);
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, bulk);
        RespValue decoded = decodeOnce(buffer);
        assertThat(((RespBulkString) decoded).bytes()).hasSize(size);
        buffer.release();
    }

    @Test
    void oldClientErrorResponseNoInjection() {
        RespError error = new RespError("ERR bad\r\ninjection");
        ByteBuf buffer = Unpooled.buffer();
        RespEncoder.write(buffer, error);
        String wire = buffer.toString(StandardCharsets.US_ASCII);
        assertThat(wire).doesNotContain("\r\ninjection");
        buffer.release();
    }

    private static RespValue decodeOnce(ByteBuf buffer) {
        List<RespValue> decoded = decodeAll(buffer);
        assertThat(decoded).hasSize(1);
        return decoded.get(0);
    }

    private static List<RespValue> decodeAll(ByteBuf buffer) {
        EmbeddedChannel channel = new EmbeddedChannel(new RespDecoder());
        try {
            channel.writeInbound(buffer.copy());
            List<RespValue> values = new ArrayList<>();
            Object item;
            while ((item = channel.readInbound()) != null) {
                values.add((RespValue) item);
            }
            return values;
        } finally {
            channel.finishAndReleaseAll();
        }
    }

    private static List<TxnMessages.Mutation> mutations(int count) {
        List<TxnMessages.Mutation> list = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            list.add(new TxnMessages.Mutation(("k" + i).getBytes(),
                    ("v" + i).getBytes(), false));
        }
        return list;
    }
}
