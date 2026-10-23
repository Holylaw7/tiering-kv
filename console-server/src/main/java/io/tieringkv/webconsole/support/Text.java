package io.tieringkv.webconsole.support;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.Base64;

/** 字节 → 文本：非法 UTF-8 退化为 base64，避免二进制值破坏 JSON。 */
public final class Text {

    public static final String BASE64_PREFIX = "base64:";

    private Text() {
    }

    public static String decode(byte[] raw) {
        if (raw == null) {
            return null;
        }
        try {
            return StandardCharsets.UTF_8.newDecoder()
                    .onMalformedInput(CodingErrorAction.REPORT)
                    .onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(raw))
                    .toString();
        } catch (CharacterCodingException ex) {
            return BASE64_PREFIX + Base64.getEncoder().encodeToString(raw);
        }
    }
}
