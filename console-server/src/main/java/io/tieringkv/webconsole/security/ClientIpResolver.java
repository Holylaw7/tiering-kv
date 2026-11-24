package io.tieringkv.webconsole.security;

import jakarta.servlet.http.HttpServletRequest;

import java.net.InetAddress;
import java.net.UnknownHostException;
import java.util.List;

/**
 * 来源客户端 IP 提取器（O3-A）：仅当直连对端位于受信任代理列表/CIDR 时才采信 X-Forwarded-For，
 * 防止攻击者直接伪造请求头绕过 IP 防撞库限流。
 */
public final class ClientIpResolver {

    private ClientIpResolver() {
    }

    public static String resolve(HttpServletRequest request, List<String> trustedProxies) {
        if (request == null) {
            return null;
        }
        String remoteAddr = normalize(request.getRemoteAddr());
        if (remoteAddr == null) {
            return null;
        }
        if (isTrustedProxy(remoteAddr, trustedProxies)) {
            String xff = request.getHeader("X-Forwarded-For");
            if (xff != null && !xff.isBlank()) {
                String[] hops = xff.split(",");
                // 从右向左跳过受信任代理，取第一个非代理客户端 IP
                for (int i = hops.length - 1; i >= 0; i--) {
                    String candidate = normalize(hops[i]);
                    if (candidate != null && !isTrustedProxy(candidate, trustedProxies)) {
                        return candidate;
                    }
                }
                String first = normalize(hops[0]);
                if (first != null) {
                    return first;
                }
            }
            String realIp = normalize(request.getHeader("X-Real-IP"));
            if (realIp != null) {
                return realIp;
            }
        }
        return remoteAddr;
    }

    static boolean isTrustedProxy(String ip, List<String> trustedProxies) {
        if (ip == null || trustedProxies == null || trustedProxies.isEmpty()) {
            return false;
        }
        for (String rule : trustedProxies) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String trimmed = rule.trim();
            if (trimmed.equalsIgnoreCase(ip)) {
                return true;
            }
            if (trimmed.contains("/") && matchesCidr(ip, trimmed)) {
                return true;
            }
        }
        return false;
    }

    private static boolean matchesCidr(String ip, String cidr) {
        int slash = cidr.indexOf('/');
        try {
            int prefixLen = Integer.parseInt(cidr.substring(slash + 1));
            byte[] target = InetAddress.getByName(ip).getAddress();
            byte[] network = InetAddress.getByName(cidr.substring(0, slash)).getAddress();
            if (target.length != network.length || prefixLen < 0 || prefixLen > target.length * 8) {
                return false;
            }
            int fullBytes = prefixLen / 8;
            int remBits = prefixLen % 8;
            for (int i = 0; i < fullBytes; i++) {
                if (target[i] != network[i]) {
                    return false;
                }
            }
            if (remBits > 0) {
                int mask = (0xFF << (8 - remBits)) & 0xFF;
                return (target[fullBytes] & mask) == (network[fullBytes] & mask);
            }
            return true;
        } catch (UnknownHostException | NumberFormatException ex) {
            return false;
        }
    }

    private static String normalize(String raw) {
        if (raw == null) {
            return null;
        }
        String s = raw.trim();
        return s.isEmpty() ? null : s;
    }
}
