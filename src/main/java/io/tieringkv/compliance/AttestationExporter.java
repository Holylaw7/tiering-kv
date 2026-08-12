package io.tieringkv.compliance;

import io.tieringkv.compliance.AttestationChain.Attestation;

import java.util.ArrayList;
import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.stream.Collectors;

/** 证明导出（ADR-0174）：JSON 交换格式 + 第三方解析。 */
public final class AttestationExporter {

    private static final Pattern FIELD = Pattern.compile(
            "\"([a-zA-Z]+)\":\"((?:[^\"\\\\]|\\\\.)*)\"");

    public String toJson(List<Attestation> attestations) {
        if (attestations == null) {
            throw new IllegalArgumentException(
                    "attestations required");
        }
        String body = attestations.stream()
                .map(attestation -> "{"
                        + field("index", String.valueOf(
                        attestation.index())) + ","
                        + field("regulation",
                        attestation.regulation()) + ","
                        + field("versionId",
                        attestation.versionId()) + ","
                        + field("violations", String.valueOf(
                        attestation.violations())) + ","
                        + field("prevHash",
                        attestation.prevHash()) + ","
                        + field("hash", attestation.hash()) + ","
                        + field("timestampMillis",
                        String.valueOf(
                                attestation.timestampMillis()))
                        + "}")
                .collect(Collectors.joining(","));
        return "[" + body + "]";
    }

    public String toJson(AttestationChain chain) {
        if (chain == null) {
            throw new IllegalArgumentException("chain required");
        }
        return toJson(chain.attestations());
    }

    /** 解析第三方 JSON 证明列表。 */
    public List<Attestation> fromJson(String json) {
        if (json == null || !json.startsWith("[")) {
            throw new IllegalArgumentException(
                    "invalid attestation json");
        }
        String trimmed = json.trim();
        if (trimmed.equals("[]")) {
            return List.of();
        }
        List<Attestation> attestations = new ArrayList<>();
        for (String object : splitObjects(trimmed)) {
            attestations.add(parseObject(object));
        }
        return attestations;
    }

    private static String field(String key, String value) {
        return "\"" + escape(key) + "\":\"" + escape(value) + "\"";
    }

    private static String escape(String value) {
        return value.replace("\\", "\\\\")
                .replace("\"", "\\\"");
    }

    private static List<String> splitObjects(String json) {
        List<String> objects = new ArrayList<>();
        int depth = 0;
        int start = -1;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (c == '{') {
                if (depth == 0) {
                    start = i;
                }
                depth++;
            } else if (c == '}') {
                depth--;
                if (depth == 0) {
                    objects.add(json.substring(start, i + 1));
                }
            }
        }
        return objects;
    }

    private static Attestation parseObject(String object) {
        Matcher matcher = FIELD.matcher(object);
        String index = null;
        String regulation = null;
        String versionId = null;
        String violations = null;
        String prevHash = null;
        String hash = null;
        String timestamp = null;
        while (matcher.find()) {
            switch (matcher.group(1)) {
                case "index" -> index = unescape(matcher.group(2));
                case "regulation" -> regulation =
                        unescape(matcher.group(2));
                case "versionId" -> versionId =
                        unescape(matcher.group(2));
                case "violations" -> violations =
                        unescape(matcher.group(2));
                case "prevHash" -> prevHash =
                        unescape(matcher.group(2));
                case "hash" -> hash = unescape(matcher.group(2));
                case "timestampMillis" -> timestamp =
                        unescape(matcher.group(2));
                default -> {
                }
            }
        }
        if (index == null || regulation == null
                || versionId == null || violations == null
                || prevHash == null || hash == null
                || timestamp == null) {
            throw new IllegalArgumentException(
                    "incomplete attestation object");
        }
        return new Attestation(Integer.parseInt(index),
                regulation, versionId, Integer.parseInt(violations),
                prevHash, hash, Long.parseLong(timestamp));
    }

    private static String unescape(String value) {
        return value.replace("\\\"", "\"")
                .replace("\\\\", "\\");
    }
}
