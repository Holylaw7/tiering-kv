package io.tieringkv.webconsole.resp;

import java.io.IOException;

/** RESP 连接/协议异常。 */
public class RespException extends IOException {

    public RespException(String message) {
        super(message);
    }

    public RespException(String message, Throwable cause) {
        super(message, cause);
    }
}
