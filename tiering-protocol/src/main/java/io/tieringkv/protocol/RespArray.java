package io.tieringkv.protocol;

import java.util.List;

/** RESP2 数组，如 {@code *2\r\n...}。 */
public record RespArray(List<RespValue> values) implements RespValue {

    public RespArray {
        values = List.copyOf(values);
    }
}
