package io.tieringkv.network.connection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RespRequestParser;
import io.tieringkv.protocol.RespEncoder;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.storage.wal.WalWriteException;
import io.tieringkv.storage.tiering.BackpressureException;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.response.ResponseBatcher;
import io.tieringkv.network.response.ResponseBuffer;

import java.util.concurrent.CompletionException;

/** 命令入站处理器：解析请求 → 执行 → 写回；协议错误写入后关闭连接。 */
public final class CommandHandler extends ChannelInboundHandlerAdapter {

    private final CommandEngine engine;
    private final MetricsRegistry metrics;
    private final ResponseSequencer sequencer = new ResponseSequencer();
    private long nextSequence = 1;
    private final long[] startTimes = new long[4096];
    private int inflight;
    private ResponseBatcher batcher;

    public CommandHandler(CommandEngine engine, MetricsRegistry metrics) {
        this.engine = engine;
        this.metrics = metrics;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        metrics.connectionOpened();
        batcher = new ResponseBatcher(
                new ResponseBuffer(ctx.alloc()), 64,
                buf -> ctx.writeAndFlush(buf));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (batcher != null) {
            batcher.close();
        }
        metrics.connectionClosed();
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RespValue value) {
            try {
                RespCommand command = RespRequestParser.parse(value);
                long sequence = nextSequence++;
                startTimes[(int) (sequence & 4095)] = System.nanoTime();
                metrics.requestStarted();
                inflight++;
                if (!metrics.accepting()) {
                    metrics.error();
                    metrics.requestCompleted(0);
                    sequencer.complete(sequence, new RespError("ERR server shutting down"),
                            ready -> offerOnEventLoop(ctx, ready));
                    return;
                }
                engine.executeAsync(command, (response, error) ->
                        ctx.executor().execute(() -> complete(ctx, sequence, response, error)));
            } catch (RespProtocolException e) {
                writeProtocolErrorAndClose(ctx, e.getMessage());
            } catch (WalWriteException e) {
                writeError(ctx, "ERR " + e.getMessage());
            } catch (BackpressureException e) {
                writeError(ctx, "ERR " + e.getMessage());
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    /** 事件循环内完成：更新指标 → 保序 → 批处理写出。 */
    private void complete(ChannelHandlerContext ctx, long sequence, RespValue response, Throwable error) {
        metrics.requestCompleted(System.nanoTime() - startTimes[(int) (sequence & 4095)]);
        Outcome outcome = error == null ? new Outcome(response, false) : mapFailure(error);
        if (outcome.close()) {
            metrics.error();
        }
        boolean last = --inflight == 0;
        sequencer.complete(sequence, outcome.value(), ready -> batcher.offer(ready, last));
        if (outcome.close()) {
            batcher.flush();
            ctx.close();
        }
    }

    private void offerOnEventLoop(ChannelHandlerContext ctx, RespValue ready) {
        ctx.executor().execute(() -> batcher.offer(ready, --inflight == 0));
    }

    private static Outcome mapFailure(Throwable error) {
        Throwable root = error instanceof CompletionException && error.getCause() != null
                ? error.getCause()
                : error;
        if (root instanceof RespProtocolException e) {
            return new Outcome(RespError.protocol(e.getMessage()), true);
        }
        if (root instanceof WalWriteException e) {
            return new Outcome(new RespError("ERR " + e.getMessage()), false);
        }
        if (root instanceof BackpressureException e) {
            return new Outcome(new RespError("ERR " + e.getMessage()), false);
        }
        return new Outcome(new RespError("ERR internal error"), true);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        Throwable root = unwrapProtocolError(cause);
        if (root instanceof RespProtocolException e) {
            writeProtocolErrorAndClose(ctx, e.getMessage());
        } else {
            ctx.close();
        }
    }

    /** ByteToMessageDecoder 会把解码异常包装为 DecoderException，此处解包。 */
    private static Throwable unwrapProtocolError(Throwable cause) {
        Throwable current = cause;
        while (current != null) {
            if (current instanceof RespProtocolException) {
                return current;
            }
            current = current.getCause();
        }
        return cause;
    }

    private void writeProtocolErrorAndClose(ChannelHandlerContext ctx, String message) {
        ByteBuf buffer = ctx.alloc().buffer();
        RespEncoder.write(buffer, RespError.protocol(message));
        ctx.writeAndFlush(buffer).addListener(ChannelFutureListener.CLOSE);
    }

    /** WAL 失败：返回错误但保持连接（不谎报成功）。 */
    private void writeError(ChannelHandlerContext ctx, String message) {
        ByteBuf buffer = ctx.alloc().buffer();
        RespEncoder.write(buffer, new RespError(message));
        ctx.writeAndFlush(buffer);
    }

    private record Outcome(RespValue value, boolean close) {
    }
}
