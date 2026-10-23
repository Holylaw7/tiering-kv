package io.tieringkv.webconsole.web.dto;

import java.util.List;

/** 一次 SCAN 结果。 */
public record KeyScanPage(String cursor, List<KeyEntry> keys, boolean finished) {
}
