package io.tieringkv.webconsole.common;

/** 业务异常：携带 HTTP 语义状态码与错误码。 */
public class BusinessException extends RuntimeException {

    private final int status;
    private final int code;

    public BusinessException(int status, int code, String message) {
        super(message);
        this.status = status;
        this.code = code;
    }

    public static BusinessException badRequest(String message) {
        return new BusinessException(400, 40000, message);
    }

    public static BusinessException notFound(String message) {
        return new BusinessException(404, 40400, message);
    }

    public static BusinessException conflict(String message) {
        return new BusinessException(409, 40900, message);
    }

    public static BusinessException unauthorized(String message) {
        return new BusinessException(401, 40100, message);
    }

    public static BusinessException upstream(String message) {
        return new BusinessException(502, 50200, message);
    }

    public int getStatus() {
        return status;
    }

    public int getCode() {
        return code;
    }
}
