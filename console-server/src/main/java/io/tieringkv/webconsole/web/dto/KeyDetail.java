package io.tieringkv.webconsole.web.dto;

/** 单键详情：值按类型展开。 */
public record KeyDetail(String key, String type, long ttl, Object value) {
}
