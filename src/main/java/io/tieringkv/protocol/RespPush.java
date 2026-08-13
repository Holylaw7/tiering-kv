package io.tieringkv.protocol;

import java.util.List;

/** RESP3 Push（>）：type + payload。 */
public record RespPush(String type, List<RespValue> values)
        implements RespValue {

    public RespPush {
        if (type == null || type.isBlank()) {
            throw new IllegalArgumentException(
                    "type required");
        }
        values = List.copyOf(values);
    }
}
