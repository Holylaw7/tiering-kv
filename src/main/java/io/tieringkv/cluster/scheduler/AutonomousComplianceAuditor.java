package io.tieringkv.cluster.scheduler;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * 自治无人值守合规自动化（ADR-0238）：执行审计链（append-only）+
 * 摘要签名（SHA-256）+ 外部审计接口。
 */
public final class AutonomousComplianceAuditor {

    private final List<String> auditChain =
            new CopyOnWriteArrayList<>();

    /** 记录合规事件并返回签名摘要。 */
    public String record(String entry) {
        if (entry == null || entry.isBlank()) {
            throw new IllegalArgumentException(
                    "entry required");
        }
        String signature = sign(entry);
        auditChain.add(entry + "|" + signature);
        return signature;
    }

    /** 导出审计链（外部审计接口）。 */
    public List<String> exportAudit() {
        return List.copyOf(auditChain);
    }

    /** 校验导出链：重算签名并验证链式完整性。 */
    public boolean verify(List<String> exported) {
        if (exported == null) {
            throw new IllegalArgumentException(
                    "exported required");
        }
        List<String> current = exportAudit();
        if (exported.size() != current.size()) {
            return false;
        }
        for (int i = 0; i < current.size(); i++) {
            String expected = current.get(i);
            String actual = exported.get(i);
            if (!expected.equals(actual)) {
                return false;
            }
            int bar = expected.lastIndexOf('|');
            String entry = expected.substring(0, bar);
            String signature = expected.substring(bar + 1);
            if (!sign(entry).equals(signature)) {
                return false;
            }
        }
        return true;
    }

    public int size() {
        return auditChain.size();
    }

    private static String sign(String entry) {
        try {
            MessageDigest digest =
                    MessageDigest.getInstance("SHA-256");
            byte[] hash = digest.digest(
                    entry.getBytes(StandardCharsets.UTF_8));
            StringBuilder hex = new StringBuilder();
            for (byte b : hash) {
                hex.append(String.format("%02x", b));
            }
            return hex.toString();
        } catch (Exception e) {
            throw new IllegalStateException(
                    "signing failed", e);
        }
    }
}
