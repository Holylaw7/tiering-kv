package io.tieringkv.storage;

/**
 * 集群路由重定向/暂不可用异常：网络层据此返回 RESP {@code -MOVED} 或 {@code -TRYAGAIN}，
 * 且不关闭客户端连接。
 */
public final class StorageRedirectException extends RuntimeException {

    private final String respMessage;

    private StorageRedirectException(String respMessage, Throwable cause) {
        super(respMessage, cause);
        this.respMessage = respMessage;
    }

    public static StorageRedirectException moved(int slot, String host, int port, Throwable cause) {
        return new StorageRedirectException("MOVED " + slot + " " + host + ":" + port, cause);
    }

    public static StorageRedirectException tryAgain(String detail, Throwable cause) {
        return new StorageRedirectException("TRYAGAIN " + detail, cause);
    }

    public String respMessage() {
        return respMessage;
    }
}
