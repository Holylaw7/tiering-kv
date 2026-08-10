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

/** 命令入站处理器：解析请求 → 执行 → 写回；协议错误写入后关闭连接。 */
public final class CommandHandler extends ChannelInboundHandlerAdapter {

    private final CommandEngine engine;

    public CommandHandler(CommandEngine engine) {
        this.engine = engine;
    }

    @Override
    public void channelRead(ChannelHandlerContext ctx, Object msg) {
        if (msg instanceof RespValue value) {
            try {
                RespCommand command = RespRequestParser.parse(value);
                ctx.writeAndFlush(engine.execute(command));
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
}
