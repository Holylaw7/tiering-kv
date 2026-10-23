package io.tieringkv.webconsole.resp;

import java.util.List;

/** RESP2 应答模型（控制台侧只读解析，不修改协议语义）。 */
public sealed interface RespReply {

    /** 简单字符串：+OK */
    record Simple(String value) implements RespReply {
    }

    /** 错误：-ERR ... */
    record Error(String message) implements RespReply {
    }

    /** 整数：:1 */
    record Integer(long value) implements RespReply {
    }

    /** 批量字符串：$n；null 表示 nil。 */
    record Bulk(byte[] value) implements RespReply {

        public boolean isNil() {
            return value == null;
        }

        public String utf8() {
            return value == null ? null : new String(value, java.nio.charset.StandardCharsets.UTF_8);
        }
    }

    /** 数组：*n；null 表示 nil array。 */
    record Array(List<RespReply> items) implements RespReply {

        public boolean isNil() {
            return items == null;
        }
    }

    static String typeName(RespReply reply) {
        return switch (reply) {
            case Simple ignored -> "simple";
            case Error ignored -> "error";
            case Integer ignored -> "integer";
            case Bulk bulk -> bulk.isNil() ? "nil" : "bulk";
            case Array array -> array.isNil() ? "nil" : "array";
        };
    }
}
