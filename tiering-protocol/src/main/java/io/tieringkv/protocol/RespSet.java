package io.tieringkv.protocol;

import java.util.List;

/** RESP3 Set（~）。 */
public record RespSet(List<RespValue> values)
        implements RespValue {

    public RespSet {
        values = List.copyOf(values);
    }
}
