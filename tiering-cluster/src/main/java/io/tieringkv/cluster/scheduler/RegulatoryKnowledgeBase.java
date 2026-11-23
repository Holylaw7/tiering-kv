package io.tieringkv.cluster.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * 监管法规库（ADR-0259）：法规版本化存储 + 条款差异计算 + 差异报告 +
 * 摘要校验 + 轮换（旧版本可验证但废弃）。
 */
public final class RegulatoryKnowledgeBase {

    /** 法规版本。 */
    public record RegulationDocument(String regulationId,
                                     String version,
                                     List<String> clauses,
                                     String digest,
                                     boolean retired) {
    }

    /** 条款差异。 */
    public record ClauseDiff(List<String> added,
                             List<String> removed,
                             List<String> changed) {
        public boolean empty() {
            return added.isEmpty() && removed.isEmpty()
                    && changed.isEmpty();
        }
    }

    private final Map<String, List<RegulationDocument>> documents =
            new ConcurrentHashMap<>();
    private final Map<String, String> activeVersion =
            new ConcurrentHashMap<>();
    private volatile RegulatoryMappingEngine mappingEngine;
    private volatile RegulatoryComplianceCertificate certificate;

    /** 注册法规版本：条款规范化 + 摘要计算。 */
    public RegulationDocument registerVersion(
            String regulationId, String version,
            List<String> clauses) {
        if (regulationId == null || regulationId.isBlank()
                || version == null || version.isBlank()
                || clauses == null || clauses.isEmpty()) {
            throw new IllegalArgumentException(
                    "regulationId, version and clauses required");
        }
        List<String> normalized = normalize(clauses);
        String digest = digest(regulationId, version, normalized);
        RegulationDocument document = new RegulationDocument(
                regulationId, version, normalized, digest, false);
        documents.computeIfAbsent(regulationId,
                ignored -> new ArrayList<>()).add(document);
        activeVersion.put(regulationId, version);
        return document;
    }

    /** 条款差异：added / removed / changed（内容变化按变更计）。 */
    public ClauseDiff diff(String regulationId, String fromVersion,
                           String toVersion) {
        RegulationDocument from = requireVersion(regulationId,
                fromVersion);
        RegulationDocument to = requireVersion(regulationId,
                toVersion);
        List<String> added = new ArrayList<>();
        List<String> removed = new ArrayList<>();
        List<String> changed = new ArrayList<>();
        Set<String> fromSet = new LinkedHashSet<>(from.clauses());
        Set<String> toSet = new LinkedHashSet<>(to.clauses());
        for (String clause : toSet) {
            if (!fromSet.contains(clause)) {
                added.add(clause);
            }
        }
        for (String clause : fromSet) {
            if (!toSet.contains(clause)) {
                removed.add(clause);
            }
        }
        for (String clause : fromSet) {
            for (String other : toSet) {
                if (clause.startsWith(other + ":")
                        || other.startsWith(clause + ":")) {
                    if (!clause.equals(other)) {
                        changed.add(other);
                    }
                }
            }
        }
        return new ClauseDiff(List.copyOf(added),
                List.copyOf(removed), List.copyOf(changed));
    }

    /** 差异报告：可导出文本。 */
    public String diffReport(String regulationId,
                             String fromVersion,
                             String toVersion) {
        ClauseDiff diff = diff(regulationId, fromVersion,
                toVersion);
        StringBuilder builder = new StringBuilder();
        builder.append("Regulation ").append(regulationId)
                .append(": ").append(fromVersion).append(" -> ")
                .append(toVersion).append(System.lineSeparator());
        builder.append("added: ")
                .append(String.join(",", diff.added()))
                .append(System.lineSeparator());
        builder.append("removed: ")
                .append(String.join(",", diff.removed()))
                .append(System.lineSeparator());
        builder.append("changed: ")
                .append(String.join(",", diff.changed()))
                .append(System.lineSeparator());
        return builder.toString();
    }

    /** 摘要校验：重算 digest 与存储一致。 */
    public boolean verify(String regulationId, String version) {
        RegulationDocument document = requireVersion(
                regulationId, version);
        return document.digest().equals(digest(regulationId,
                version, document.clauses()));
    }

    /** 轮换：标记废弃，历史版本仍可验证。 */
    public void retire(String regulationId, String version) {
        List<RegulationDocument> versions = documents
                .get(regulationId);
        if (versions == null) {
            throw new IllegalArgumentException(
                    "unknown regulation " + regulationId);
        }
        for (int i = 0; i < versions.size(); i++) {
            RegulationDocument current = versions.get(i);
            if (current.version().equals(version)) {
                if (current.retired()) {
                    throw new IllegalArgumentException(
                            "version already retired "
                                    + version);
                }
                versions.set(i, new RegulationDocument(
                        current.regulationId(), current.version(),
                        current.clauses(), current.digest(), true));
                return;
            }
        }
        throw new IllegalArgumentException(
                "unknown version " + version);
    }

    public RegulationDocument active(String regulationId) {
        String version = activeVersion.get(regulationId);
        if (version == null) {
            throw new IllegalArgumentException(
                    "unknown regulation " + regulationId);
        }
        return requireVersion(regulationId, version);
    }

    public List<RegulationDocument> versions(
            String regulationId) {
        List<RegulationDocument> list = documents
                .get(regulationId);
        return list == null ? List.of() : List.copyOf(list);
    }

    public void attachMappingEngine(
            RegulatoryMappingEngine engine) {
        if (engine == null) {
            throw new IllegalArgumentException(
                    "engine required");
        }
        this.mappingEngine = engine;
    }

    public void attachCertificate(
            RegulatoryComplianceCertificate certificate) {
        if (certificate == null) {
            throw new IllegalArgumentException(
                    "certificate required");
        }
        this.certificate = certificate;
    }

    /** 证据映射：联动法规映射引擎。 */
    public String evidence(String eventType) {
        if (mappingEngine == null) {
            throw new IllegalStateException(
                    "mapping engine not attached");
        }
        return mappingEngine.mapEvent(eventType);
    }

    /** 合规证书签发：基于法规版本摘要。 */
    public RegulatoryComplianceCertificate.Certificate issueCertificate(
            String regulationId, String issuer) {
        if (certificate == null) {
            throw new IllegalStateException(
                    "certificate not attached");
        }
        RegulationDocument document = active(regulationId);
        return certificate.issue(document.digest(), issuer);
    }

    private RegulationDocument requireVersion(
            String regulationId, String version) {
        List<RegulationDocument> versions = documents
                .get(regulationId);
        if (versions == null) {
            throw new IllegalArgumentException(
                    "unknown regulation " + regulationId);
        }
        return versions.stream()
                .filter(document -> document.version()
                        .equals(version))
                .findFirst()
                .orElseThrow(() -> new IllegalArgumentException(
                        "unknown version " + version));
    }

    private static List<String> normalize(List<String> clauses) {
        Set<String> set = new LinkedHashSet<>();
        for (String clause : clauses) {
            if (clause == null || clause.isBlank()) {
                throw new IllegalArgumentException(
                        "blank clause");
            }
            set.add(clause.trim());
        }
        return List.copyOf(set);
    }

    private static String digest(String regulationId,
                                 String version,
                                 List<String> clauses) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            String payload = regulationId + "|" + version + "|"
                    + String.join("|", clauses);
            byte[] hash = digest.digest(
                    payload.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "digest failed", e);
        }
    }
}
