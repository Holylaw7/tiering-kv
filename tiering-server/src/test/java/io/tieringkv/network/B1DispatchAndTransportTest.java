package io.tieringkv.network;

import io.netty.channel.EventLoopGroup;
import io.netty.channel.socket.nio.NioServerSocketChannel;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.CommandRegistry;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RespRequestParser;
import io.tieringkv.config.ServerConfig;
import io.tieringkv.execution.KeyShardExecutor;
import io.tieringkv.network.connection.ResponseSequencer;
import io.tieringkv.network.tcp.NettyTransportFactory;
import io.tieringkv.network.tcp.TieringKvServer;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespInteger;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.MutableClock;
import io.tieringkv.storage.memory.MemTable;
import io.tieringkv.storage.memory.MemoryManager;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * B1 网络传输、无锁环形保序器、热命令零分配解析与分片批消费回归测试。
 */
class B1DispatchAndTransportTest {

    @Test
    void responseSequencerHandlesRingWrapAroundAndDeepOverflowInStrictOrder() {
        ResponseSequencer sequencer = new ResponseSequencer();
        List<Long> emitted = new ArrayList<>();

        // 乱序提交 2..300（跨越 256 环形窗口触发稀疏溢出桶）
        for (long seq = 300; seq >= 2; seq--) {
            sequencer.complete(seq, new RespInteger(seq), val -> emitted.add(((RespInteger) val).value()));
        }
        assertThat(emitted).isEmpty();

        // 提交 seq=1 应一次性顺序释放 1..300
        sequencer.complete(1, new RespInteger(1), val -> emitted.add(((RespInteger) val).value()));
        assertThat(emitted).hasSize(300);
        for (int i = 0; i < 300; i++) {
            assertThat(emitted.get(i)).isEqualTo(i + 1L);
        }

        // 继续绕回环形缓冲（301..600 乱序再补齐）
        emitted.clear();
        for (long seq = 302; seq <= 600; seq++) {
            sequencer.complete(seq, new RespInteger(seq), val -> emitted.add(((RespInteger) val).value()));
        }
        assertThat(emitted).isEmpty();
        sequencer.complete(301, new RespInteger(301), val -> emitted.add(((RespInteger) val).value()));
        assertThat(emitted).hasSize(300);
        assertThat(emitted.get(0)).isEqualTo(301L);
        assertThat(emitted.get(299)).isEqualTo(600L);
    }

    @Test
    void nettyTransportFactorySupportsAutoAndForcedNio() {
        String previous = System.getProperty(NettyTransportFactory.TRANSPORT_PROPERTY);
        try {
            System.setProperty(NettyTransportFactory.TRANSPORT_PROPERTY, "nio");
            assertThat(NettyTransportFactory.useEpoll()).isFalse();
            assertThat(NettyTransportFactory.transportName()).isEqualTo("nio");
            assertThat(NettyTransportFactory.serverChannelClass()).isEqualTo(NioServerSocketChannel.class);
            EventLoopGroup group = NettyTransportFactory.newWorkerGroup(1);
            group.shutdownGracefully().syncUninterruptibly();

            System.clearProperty(NettyTransportFactory.TRANSPORT_PROPERTY);
            assertThat(NettyTransportFactory.transportName()).isIn("epoll", "nio");
        } finally {
            if (previous == null) {
                System.clearProperty(NettyTransportFactory.TRANSPORT_PROPERTY);
            } else {
                System.setProperty(NettyTransportFactory.TRANSPORT_PROPERTY, previous);
            }
        }
    }

    @Test
    void respRequestParserInternsHotCommandsCaseInsensitively() {
        RespCommand get = parse("GeT", "k1");
        RespCommand set = parse("SET", "k1", "v1");
        RespCommand ping = parse("PiNg");
        RespCommand zrange = parse("ZRaNgE", "z", "0", "-1");
        RespCommand custom = parse("VECTOR.SEARCH", "default");

        assertThat(get.name()).isSameAs("get");
        assertThat(set.name()).isSameAs("set");
        assertThat(ping.name()).isSameAs("ping");
        assertThat(ping.args()).isEmpty();
        assertThat(zrange.name()).isSameAs("zrange");
        assertThat(custom.name()).isEqualTo("vector.search");
    }

    @Test
    void keyShardExecutorBatchDrainPreservesSameKeyOrderAndRaceFreeAwaitIdle() throws Exception {
        try (KeyShardExecutor executor = new KeyShardExecutor(8, "b1-batch")) {
            List<Integer> sameKeyOrder = new CopyOnWriteArrayList<>();
            AtomicInteger totalDone = new AtomicInteger();
            byte[] hotKey = "same-key".getBytes(StandardCharsets.UTF_8);

            for (int i = 0; i < 1000; i++) {
                int idx = i;
                assertThat(executor.submit(hotKey, () -> {
                    sameKeyOrder.add(idx);
                    totalDone.incrementAndGet();
                })).isTrue();
                byte[] other = ("other-" + idx).getBytes(StandardCharsets.UTF_8);
                assertThat(executor.submit(other, () -> {
                    if (idx % 100 == 0) {
                        throw new IllegalStateException("isolated boom");
                    }
                    totalDone.incrementAndGet();
                })).isTrue();
            }

            assertThat(executor.awaitIdle(5000)).isTrue();
            assertThat(sameKeyOrder).hasSize(1000);
            for (int i = 0; i < 1000; i++) {
                assertThat(sameKeyOrder.get(i)).isEqualTo(i);
            }
            // 1000 sameKey + 990 non-throwing other tasks
            assertThat(totalDone.get()).isEqualTo(1990);
            assertThat(executor.metrics().snapshot().operations()).isEqualTo(2000L);
            assertThat(executor.metrics().snapshot().activeTasks()).isZero();
        }
    }

    @Test
    void deepPipeline256CompletesInStrictOrderWithFlushConsolidation() throws Exception {
        MemTable memTable = MemTable.createForTest(new MutableClock(0), new MemoryManager(1 << 30));
        try (KeyShardExecutor executor = new KeyShardExecutor(8, "b1-pipe");
             TieringKvServer server = new TieringKvServer(
                     new ServerConfig("127.0.0.1", 0),
                     new CommandEngine(CommandRegistry.createDefault(), memTable, executor))) {
            server.start();
            assertThat(server.transportName()).isIn("epoll", "nio");
            try (TestRespClient client = new TestRespClient(server.boundPort())) {
                StringBuilder sets = new StringBuilder();
                for (int i = 0; i < 256; i++) {
                    sets.append(TestRespClient.command("SET", "k:" + i, "val:" + i));
                }
                client.send(sets.toString());
                for (int i = 0; i < 256; i++) {
                    assertThat(client.readResponse()).isEqualTo("+OK");
                }

                StringBuilder gets = new StringBuilder();
                for (int i = 0; i < 256; i++) {
                    gets.append(TestRespClient.command("GET", "k:" + i));
                }
                client.send(gets.toString());
                for (int i = 0; i < 256; i++) {
                    String expected = "val:" + i;
                    assertThat(client.readResponse())
                            .isEqualTo("$" + expected.length() + "\r\n" + expected + "\r\n");
                }
            }
        }
    }

    private static RespCommand parse(String... parts) {
        List<RespValue> values = new ArrayList<>(parts.length);
        for (String p : parts) {
            values.add(new RespBulkString(p.getBytes(StandardCharsets.UTF_8)));
        }
        return RespRequestParser.parse(new RespArray(values));
    }
}
