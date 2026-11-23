package io.tieringkv.protocol;

/** RESP3 Big Number（(）。 */
public record RespBigNumber(String value) implements RespValue {

    public RespBigNumber {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(
                    "value required");
        }
    }
}
