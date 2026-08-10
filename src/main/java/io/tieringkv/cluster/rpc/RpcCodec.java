package io.tieringkv.cluster.rpc;

import io.netty.buffer.ByteBuf;
import io.netty.channel.ChannelHandlerContext;
import io.netty.handler.codec.ByteToMessageDecoder;
import io.netty.handler.codec.MessageToByteEncoder;

import java.util.List;

/** RPC 帧编解码（ADR-0041）：长度前缀 + requestId + type + payload。 */
public final class RpcCodec {

    private RpcCodec() {
    }

    public static final class Encoder extends MessageToByteEncoder<RpcFrame> {
        @Override
        protected void encode(ChannelHandlerContext ctx, RpcFrame frame, ByteBuf out) {
            out.writeInt(13 + frame.payload().length);
            out.writeLong(frame.requestId());
            out.writeByte(frame.type().wireValue());
            out.writeInt(frame.payload().length);
            out.writeBytes(frame.payload());
        }
    }

    public static final class Decoder extends ByteToMessageDecoder {
        @Override
        protected void decode(ChannelHandlerContext ctx, ByteBuf in, List<Object> out) {
            if (in.readableBytes() < 4) {
                return;
            }
            in.markReaderIndex();
            int length = in.readInt();
            if (length < 13 || length > 64 * 1024 * 1024) {
                throw new IllegalArgumentException("bad frame length " + length);
            }
            if (in.readableBytes() < length) {
                in.resetReaderIndex();
                return;
            }
            long requestId = in.readLong();
            RpcMessageType type = RpcMessageType.fromWire(in.readByte());
            int payloadLength = in.readInt();
            byte[] payload = new byte[payloadLength];
            in.readBytes(payload);
            out.add(new RpcFrame(requestId, type, payload));
        }
    }
}
