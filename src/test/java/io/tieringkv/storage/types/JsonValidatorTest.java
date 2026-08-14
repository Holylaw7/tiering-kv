package io.tieringkv.storage.types;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** JSON 最小结构校验（ADR-0320 M2 增强）。 */
class JsonValidatorTest {

    @ParameterizedTest(name = "json {0}")
    @ValueSource(strings = {
            "{}",
            "[]",
            "{\"a\":1}",
            "[1,2,{\"b\":\"x\"}]",
            "{\"s\":\"with \\\"escape\\\"\"}",
            "\"plain string\"",
            "123",
            "-1.5e3",
            "true",
            "false",
            "null",
            "  {\"a\":[1,2]}  "
    })
    void validJsonAccepted(String json) {
        assertThatCode(() -> JsonValidator.validate(json))
                .doesNotThrowAnyException();
    }

    @ParameterizedTest(name = "json {0}")
    @ValueSource(strings = {
            "",
            "   ",
            "{",
            "[1,2",
            "{}{}",
            "[1,2]x",
            "{\"unclosed}",
            "abc def",
            "}"
    })
    void invalidJsonRejected(String json) {
        assertThatThrownBy(() -> JsonValidator.validate(json))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void nullRejected() {
        assertThatThrownBy(() -> JsonValidator.validate(null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void encodeJsonNowValidates() {
        assertThatThrownBy(() ->
                MultiModelCodec.encodeJson("[1,2"))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatCode(() ->
                MultiModelCodec.encodeJson("{\"a\":[1,2]}"))
                .doesNotThrowAnyException();
    }
}
