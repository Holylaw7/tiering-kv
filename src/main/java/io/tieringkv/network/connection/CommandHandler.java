package io.tieringkv.network.connection;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelFutureListener;
import io.netty.channel.ChannelHandlerContext;
import io.netty.channel.ChannelInboundHandlerAdapter;
import io.netty.handler.timeout.IdleState;
import io.netty.handler.timeout.IdleStateEvent;
import io.tieringkv.command.CommandEngine;
import io.tieringkv.command.RespCommand;
import io.tieringkv.command.RespRequestParser;
import io.tieringkv.protocol.RespEncoder;
import io.tieringkv.protocol.RespError;
import io.tieringkv.protocol.RespArray;
import io.tieringkv.protocol.RespBulkString;
import io.tieringkv.protocol.RespPush;
import io.tieringkv.protocol.RespSimpleString;
import io.tieringkv.protocol.RespVersion;
import io.tieringkv.protocol.RespProtocolException;
import io.tieringkv.protocol.RespValue;
import io.tieringkv.pubsub.ConnectionSubscriber;
import io.tieringkv.session.ConnectionContext;
import io.tieringkv.storage.StorageRedirectException;
import io.tieringkv.storage.wal.WalWriteException;
import io.tieringkv.storage.tiering.BackpressureException;
import io.tieringkv.monitor.MetricsRegistry;
import io.tieringkv.network.response.ResponseBatcher;
import io.tieringkv.network.response.ResponseBuffer;

import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.IntSupplier;

/** 命令入站处理器：解析请求 → 执行 → 写回；协议错误写入后关闭连接。 */
public final class CommandHandler extends ChannelInboundHandlerAdapter {

    private static final org.slf4j.Logger LOG =
            org.slf4j.LoggerFactory.getLogger(CommandHandler.class);

    private final CommandEngine engine;
    private final MetricsRegistry metrics;
    private final AtomicInteger activeConnections;
    private final IntSupplier maxClients;
    private final ResponseSequencer sequencer = new ResponseSequencer();
    private long nextSequence = 1;
    private final java.util.Map<Long, Long> startTimes = new java.util.HashMap<>();
    private final java.util.Deque<PendingCommand> deferred = new java.util.ArrayDeque<>();
    private boolean barrierRunning;
    private int inflight;
    private boolean accepted;

    private record PendingCommand(long sequence, io.tieringkv.command.RespCommand command) { }
    private ResponseBatcher batcher;
    private ConnectionContext context;

    public CommandHandler(CommandEngine engine, MetricsRegistry metrics) {
        this(engine, metrics, null, null);
    }

    public CommandHandler(CommandEngine engine, MetricsRegistry metrics,
                          AtomicInteger activeConnections, IntSupplier maxClients) {
        this.engine = engine;
        this.metrics = metrics;
        this.activeConnections = activeConnections;
        this.maxClients = maxClients;
    }

    @Override
    public void channelActive(ChannelHandlerContext ctx) {
        if (activeConnections != null && maxClients != null) {
            int current = activeConnections.incrementAndGet();
            int limit = maxClients.getAsInt();
            if (limit > 0 && current > limit) {
                activeConnections.decrementAndGet();
                ByteBuf buf = ctx.alloc().buffer();
                RespEncoder.write(buf, new RespError("ERR max number of clients reached"));
                ctx.writeAndFlush(buf).addListener(ChannelFutureListener.CLOSE);
                return;
            }
        }
        accepted = true;
        metrics.connectionOpened();
        context = new ConnectionContext();
        batcher = new ResponseBatcher(
                new ResponseBuffer(ctx.alloc()), 64,
                buf -> ctx.writeAndFlush(buf));
    }

    @Override
    public void channelInactive(ChannelHandlerContext ctx) {
        if (!accepted) {
            return;
        }
        if (activeConnections != null) {
            activeConnections.decrementAndGet();
        }
        deferred.clear();
        startTimes.clear();
        if (context != null) {
            context.cleanup();
        }
        if (batcher != null) {
            batcher.close();
        }
        metrics.connectionClosed();
    }

    @Override
    public void userEventTriggered(ChannelHandlerContext ctx, Object evt) throws Exception {
        if (evt instanceof IdleStateEvent event && event.state() == IdleState.READER_IDLE) {
            ctx.close();
            return;
        }
        super.userEventTriggered(ctx, evt);
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (!accepted) {
            return;
        }
        if (msg instanceof RespValue value) {
            try {
                RespCommand command;
                ConnectionContext previous = ConnectionContext.current();
                ConnectionContext.attach(context);
                try {
                    command = RespRequestParser.parse(value);
                } finally {
                    ConnectionContext.restore(previous);
                }
                long sequence = nextSequence++;
                if (!metrics.accepting()) {
                    metrics.error();
                    metrics.requestStarted();
                    metrics.requestCompleted(0);
                    sequencer.complete(sequence, new RespError("ERR server shutting down"),
                            ready -> offerOnEventLoop(ctx, ready));
                    return;
                }
                PendingCommand pending = new PendingCommand(sequence, command);
                if (!deferred.isEmpty() || barrierRunning || (needsSerialBarrier(command) && inflight > 0)) {
                    deferred.addLast(pending);
                    return;
                }
                dispatchCommand(ctx, pending);
            } catch (RespProtocolException e) {
                writeProtocolErrorAndClose(ctx, e.getMessage());
            } catch (WalWriteException e) {
                writeError(ctx, "ERR " + e.getMessage());
            } catch (BackpressureException e) {
                writeError(ctx, "ERR " + e.getMessage());
            } catch (StorageRedirectException e) {
                writeError(ctx, e.respMessage());
            } catch (OutOfMemoryError oom) {
                // TD-083：堆耗尽时不能让事件循环线程静默死亡（进程还在、
                // 连接全被重置）。此处关闭连接并记录，交由上层观测/重启。
                handleOutOfMemory(ctx, oom);
            }
        } else {
            ctx.fireChannelRead(msg);
        }
    }

    private boolean needsSerialBarrier(RespCommand command) {
        if (context != null && context.inMulti()) {
            return true;
        }
        return switch (command.name()) {
            case "auth", "acl", "config",
                 "multi", "exec", "discard", "watch", "unwatch",
                 "hello", "flushdb", "flushall", "dbsize", "scan",
                 "mget", "mset", "msetnx", "blpop", "brpop", "keys", "client",
                 "subscribe", "unsubscribe", "psubscribe", "punsubscribe" -> true;
            case "del", "exists" -> command.args().size() > 1;
            default -> false;
        };
    }

    private void dispatchCommand(ChannelHandlerContext ctx, PendingCommand pending) {
        if (needsSerialBarrier(pending.command())) {
            barrierRunning = true;
        }
        startTimes.put(pending.sequence(), System.nanoTime());
        metrics.requestStarted();
        inflight++;
        ConnectionContext previous = ConnectionContext.current();
        ConnectionContext.attach(context);
        try {
            engine.executeAsync(pending.command(), (response, error) ->
                    ctx.executor().execute(() -> complete(ctx, pending.sequence(), response, error)));
        } finally {
            ConnectionContext.restore(previous);
        }
    }

    private void drainDeferred(ChannelHandlerContext ctx) {
        while (!deferred.isEmpty() && !barrierRunning) {
            PendingCommand next = deferred.peekFirst();
            boolean barrier = needsSerialBarrier(next.command());
            if (barrier && inflight > 0) {
                return;
            }
            deferred.pollFirst();
            dispatchCommand(ctx, next);
            if (barrierRunning) {
                return;
            }
        }
    }

    /** 事件循环内完成：更新指标 → 保序 → 批处理写出。 */
    private void complete(ChannelHandlerContext ctx, long sequence, RespValue response, Throwable error) {
        Long startedAt = startTimes.remove(sequence);
        long latencyNanos = startedAt == null ? 0L : System.nanoTime() - startedAt;
        metrics.requestCompleted(latencyNanos);
        barrierRunning = false;
        boolean last = --inflight == 0;
        if (!ctx.channel().isActive()) {
            deferred.clear();
            return;
        }
        Outcome outcome = error == null ? new Outcome(response, false) : mapFailure(error);
        if (outcome.close()) {
            metrics.error();
        }
        batcher.setVersion(context.version());
        drainPubSub(batcher);
        sequencer.complete(sequence, outcome.value(), ready -> batcher.offer(ready, last));
        if (outcome.close()) {
            batcher.flush();
            ctx.close();
            return;
        }
        drainDeferred(ctx);
    }

    /** 事件循环内：把连接订阅队列编码为 Push（RESP3）/数组（RESP2）。 */
    private void drainPubSub(ResponseBatcher batcher) {
        ConnectionSubscriber subscriber = context.subscriber();
        ConnectionSubscriber.Message message;
        while ((message = subscriber.poll()) != null) {
            RespValue push;
            if (context.version() == RespVersion.RESP3) {
                push = new RespPush("message", List.of(
                        new RespBulkString(message.channel()
                                .getBytes(java.nio.charset
                                        .StandardCharsets.UTF_8)),
                        new RespBulkString(message.payload())));
            } else {
                push = new RespArray(List.of(
                        new RespSimpleString("message"),
                        new RespBulkString(message.channel()
                                .getBytes(java.nio.charset
                                        .StandardCharsets.UTF_8)),
                        new RespBulkString(message.payload())));
            }
            batcher.offer(push, false);
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
        if (root instanceof StorageRedirectException e) {
            return new Outcome(new RespError(e.respMessage()), false);
        }
        return new Outcome(new RespError("ERR internal error"), true);
    }

    @Override
    public void exceptionCaught(ChannelHandlerContext ctx, Throwable cause) {
        if (cause instanceof OutOfMemoryError oom) {
            handleOutOfMemory(ctx, oom);
            return;
        }
        Throwable root = unwrapProtocolError(cause);
        if (root instanceof RespProtocolException e) {
            writeProtocolErrorAndClose(ctx, e.getMessage());
        } else {
            ctx.close();
        }
    }

    /** OOM 兜底：记录后关闭连接，避免异常逃逸导致事件循环线程终止。 */
    private static void handleOutOfMemory(ChannelHandlerContext ctx, OutOfMemoryError oom) {
        LOG.error("命令执行期间堆内存耗尽，关闭连接 {}（TD-083）", ctx.channel(), oom);
        ctx.close();
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
