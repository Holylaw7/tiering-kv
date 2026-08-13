package io.tieringkv.protocol;

import java.util.List;

/** RESP3 Map（%）：键值平铺列表。 */
public record RespMap(List<RespValue> pairs)
        implements RespValue {

    public RespMap {
        pairs = List.copyOf(pairs);
    }
}
