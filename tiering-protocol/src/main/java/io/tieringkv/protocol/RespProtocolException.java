package io.tieringkv.protocol;

/**
 * RESP 协议错误。消息不含前缀，由编码层统一包装为
 * {@code -ERR Protocol error: <message>}。
 */
public final class RespProtocolException extends RuntimeException {

    public RespProtocolException(String message) {
        super(message);
    }
}
