package io.tieringkv.webconsole.web.dto;

/** 键列表项。 */
public record KeyEntry(String key, String type, long ttl) {
}
