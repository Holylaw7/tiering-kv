package io.tieringkv.security.network;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/** 网络隔离策略（ADR-0161）：跨域通信默认拒绝 + 白名单。 */
public final class IsolationPolicy {

    private final Map<String, NetworkIsolationDomain> domains =
            new ConcurrentHashMap<>();
    private final Set<String> whitelist =
            ConcurrentHashMap.newKeySet();

    public void register(NetworkIsolationDomain domain) {
        if (domain == null) {
            throw new IllegalArgumentException(
                    "domain required");
        }
        domains.put(domain.tenantId(), domain);
    }

    /** 显式授权双向通信。 */
    public void allow(String tenantA, String tenantB) {
        requireDomain(tenantA);
        requireDomain(tenantB);
        whitelist.add(pair(tenantA, tenantB));
    }

    /** 撤销授权。 */
    public void deny(String tenantA, String tenantB) {
        whitelist.remove(pair(tenantA, tenantB));
    }

    /** 通信判定：同域或白名单内允许，否则默认拒绝。 */
    public boolean canCommunicate(String from, String to) {
        if (!domains.containsKey(from) || !domains.containsKey(to)) {
            return false;
        }
        if (from.equals(to)) {
            return true;
        }
        return whitelist.contains(pair(from, to));
    }

    public NetworkIsolationDomain domain(String tenantId) {
        NetworkIsolationDomain domain = domains.get(tenantId);
        if (domain == null) {
            throw new IllegalArgumentException(
                    "unknown tenant domain " + tenantId);
        }
        return domain;
    }

    public boolean isPrivate(String tenantId) {
        return domain(tenantId).privateNetwork();
    }

    public List<String> whitelistEntries() {
        return List.copyOf(whitelist);
    }

    public int domainCount() {
        return domains.size();
    }

    public java.util.Set<String> tenantIds() {
        return Set.copyOf(domains.keySet());
    }

    public void clearWhitelist() {
        whitelist.clear();
    }

    private void requireDomain(String tenantId) {
        if (!domains.containsKey(tenantId)) {
            throw new IllegalArgumentException(
                    "unknown tenant domain " + tenantId);
        }
    }

    /** 规范化为有序 pair（双向同 key）。 */
    private static String pair(String a, String b) {
        return a.compareTo(b) <= 0 ? a + ":" + b : b + ":" + a;
    }
}
