package io.tieringkv.webconsole.resp;

import io.tieringkv.webconsole.config.ConsoleProperties;
import io.tieringkv.webconsole.domain.DbInstance;
import io.tieringkv.webconsole.service.RespGateway;
import org.junit.jupiter.api.Test;

import java.io.BufferedInputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** O2-A：RESP Pipeline 批处理顺序一致性与异常连接销毁（零许可泄漏）验证。 */
class RespClientPipelineTest {

    @Test
    void executeBatchPreservesOrderAcross100MixedCommands() throws Exception {
        try (ServerSocket server = new ServerSocket(0)) {
            Thread worker = Thread.startVirtualThread(() -> {
                try (Socket s = server.accept()) {
                    BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                    OutputStream out = s.getOutputStream();
                    for (int i = 0; i < 100; i++) {
                        RespCodec.read(in);
                        String resp = switch (i % 4) {
                            case 0 -> "+OK-" + i + "\r\n";
                            case 1 -> ":" + i + "\r\n";
                            case 2 -> {
                                String body = "val-" + i;
                                yield "$" + body.length() + "\r\n" + body + "\r\n";
                            }
                            default -> "-ERR boom-" + i + "\r\n";
                        };
                        out.write(resp.getBytes(StandardCharsets.UTF_8));
                    }
                    out.flush();
                } catch (IOException ignored) {
                    // 测试服务端退出
                }
            });

            List<List<byte[]>> batch = new ArrayList<>(100);
            for (int i = 0; i < 100; i++) {
                batch.add(RespClient.commandBytes("ECHO", "cmd-" + i));
            }

            try (RespClient client = RespClient.open("127.0.0.1", server.getLocalPort(), 2000, 2000)) {
                List<RespReply> replies = client.executeBatch(batch);
                assertThat(replies).hasSize(100);
                for (int i = 0; i < 100; i++) {
                    RespReply r = replies.get(i);
                    switch (i % 4) {
                        case 0 -> assertThat(((RespReply.Simple) r).value()).isEqualTo("OK-" + i);
                        case 1 -> assertThat(((RespReply.Integer) r).value()).isEqualTo(i);
                        case 2 -> assertThat(((RespReply.Bulk) r).utf8()).isEqualTo("val-" + i);
                        default -> assertThat(((RespReply.Error) r).message()).isEqualTo("ERR boom-" + i);
                    }
                }
                assertThat(client.isUsable()).isTrue();
            }
            worker.join(2000);
        }
    }

    @Test
    void midBatchDisconnectMarksClientBrokenAndPoolDiscardsWithoutLeakingPermits() throws Exception {
        AtomicInteger connections = new AtomicInteger();
        try (ServerSocket server = new ServerSocket(0)) {
            Thread acceptor = Thread.startVirtualThread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) {
                        int n = connections.incrementAndGet();
                        BufferedInputStream in = new BufferedInputStream(s.getInputStream());
                        OutputStream out = s.getOutputStream();
                        if (n == 1) {
                            // 读取 5 条后只回 2 条即主动断连，模拟半读管线失败
                            for (int i = 0; i < 5; i++) {
                                RespCodec.read(in);
                            }
                            out.write("+OK\r\n+OK\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        } else {
                            RespCodec.read(in);
                            out.write("+PONG\r\n".getBytes(StandardCharsets.UTF_8));
                            out.flush();
                        }
                    } catch (IOException ignored) {
                        break;
                    }
                }
            });

            ConsoleProperties props = new ConsoleProperties();
            props.getPool().setMaxTotal(1);
            props.getPool().setMaxIdle(1);
            props.getPool().setBorrowTimeoutMs(500);
            props.getMetrics().setConnectTimeoutMs(1000);
            props.getMetrics().setReadTimeoutMs(1000);
            RespGateway gateway = new RespGateway(props);

            DbInstance inst = new DbInstance();
            inst.setId(91L);
            inst.setName("pipe-drop");
            inst.setHost("127.0.0.1");
            inst.setPort(server.getLocalPort());

            List<List<byte[]>> batch = List.of(
                    RespClient.commandBytes("PING"),
                    RespClient.commandBytes("PING"),
                    RespClient.commandBytes("PING"),
                    RespClient.commandBytes("PING"),
                    RespClient.commandBytes("PING"));

            assertThatThrownBy(() -> gateway.executeBatch(inst, batch))
                    .isInstanceOf(IOException.class);

            // maxTotal=1：若未正确 discard 归还许可，下一次请求必然超时失败
            RespReply recovered = gateway.execute(inst, "PING");
            assertThat(((RespReply.Simple) recovered).value()).isEqualTo("PONG");
            assertThat(connections.get()).isGreaterThanOrEqualTo(2);

            gateway.shutdown();
            server.close();
            acceptor.join(2000);
        }
    }
}
