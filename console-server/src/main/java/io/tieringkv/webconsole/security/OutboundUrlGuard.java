package io.tieringkv.webconsole.security;

import io.tieringkv.webconsole.common.BusinessException;
import io.tieringkv.webconsole.config.ConsoleProperties;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.stereotype.Component;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * 出站 Webhook URL 安全校验器（Wave 1 S1）：
 * 防止通过告警通知渠道发起 SSRF 与 DNS Rebinding（探测环回地址、内网网段及云厂商 IMDS 元数据端点）。
 *
 * <p>核心安全不变量：
 * <ul>
 *   <li>注册期拦截非法协议、内嵌 userinfo、尾部点号变体、私网/保留/云元数据字面量及可解析私网域名；</li>
 *   <li>发包期单次解析并校验全部 A/AAAA 记录（含 DNS 超时 fail-closed），返回已固定目标 IP 的
 *       {@link VerifiedTarget}，由 {@link PinnedWebhookHttpClient} 直接以该 {@link InetAddress}
 *       建立 TCP/TLS 连接，从根本上消除校验与连接之间的 DNS Rebinding TOCTOU 窗口；</li>
 *   <li>白名单 {@code console.security.webhook-allowed-cidrs} 仅支持服务端配置，且主机名白名单
 *       不得绕过云元数据段（169.254.0.0/16、100.100.100.200/32）。</li>
 * </ul>
 */
@Component
public class OutboundUrlGuard {

    /** 可注入 DNS 解析器，便于单测验证 DNS Rebinding、双栈与超时而不依赖外网解析。 */
    @FunctionalInterface
    public interface HostResolver {
        InetAddress[] resolveAll(String host) throws UnknownHostException;
    }

    /** 经过单次解析与全记录安全校验的已固定出站目标，调用方必须直接连 pinnedAddress，不得二次解析。 */
    public record VerifiedTarget(
            String scheme,
            String originalHost,
            int port,
            String pathAndQuery,
            InetAddress pinnedAddress,
            List<InetAddress> allValidatedAddresses) {

        public VerifiedTarget {
            allValidatedAddresses = allValidatedAddresses == null ? List.of() : List.copyOf(allValidatedAddresses);
        }

        public String hostHeaderValue() {
            boolean defaultPort = ("http".equalsIgnoreCase(scheme) && port == 80)
                    || ("https".equalsIgnoreCase(scheme) && port == 443);
            String formattedHost = originalHost.contains(":") ? "[" + originalHost + "]" : originalHost;
            return defaultPort ? formattedHost : formattedHost + ":" + port;
        }
    }

    static final long DEFAULT_DNS_TIMEOUT_MS = 2_000L;

    private static final AtomicInteger DNS_THREAD_SEQ = new AtomicInteger(1);
    private static final ExecutorService DNS_EXECUTOR = new ThreadPoolExecutor(
            0, 8, 30L, TimeUnit.SECONDS,
            new SynchronousQueue<>(),
            runnable -> {
                Thread t = new Thread(runnable, "webhook-dns-resolver-" + DNS_THREAD_SEQ.getAndIncrement());
                t.setDaemon(true);
                return t;
            },
            new ThreadPoolExecutor.AbortPolicy());

    private static final Set<String> FORBIDDEN_HOSTS = Set.of(
            "localhost",
            "localhost.localdomain",
            "ip6-localhost",
            "ip6-loopback",
            "metadata.google.internal",
            "metadata.goog",
            "metadata",
            "instance-data"
    );

    /** 云元数据高危地址段：绝不允许被“主机名白名单”隐式放行，仅允许显式 IP/CIDR 命中。 */
    private static final String[] CLOUD_METADATA_CIDRS = {
            "169.254.0.0/16",     // AWS / GCP / Azure / OpenStack IMDS (169.254.169.254)
            "100.100.100.200/32"  // Alibaba Cloud IMDS
    };

    private static final String[] BLOCKED_IPV4_CIDRS = {
            "0.0.0.0/8",          // RFC 1122 "this network"
            "10.0.0.0/8",         // RFC 1918 private
            "100.64.0.0/10",      // RFC 6598 CGNAT（含阿里云元数据 100.100.100.200）
            "127.0.0.0/8",        // Loopback
            "169.254.0.0/16",     // Link-local / AWS, GCP, Azure IMDS
            "172.16.0.0/12",      // RFC 1918 private
            "192.0.0.0/24",       // IETF protocol assignments
            "192.0.2.0/24",       // TEST-NET-1
            "192.88.99.0/24",     // 6to4 relay anycast (deprecated)
            "192.168.0.0/16",     // RFC 1918 private
            "198.18.0.0/15",      // Benchmarking
            "198.51.100.0/24",    // TEST-NET-2
            "203.0.113.0/24",     // TEST-NET-3
            "224.0.0.0/4",        // Multicast
            "240.0.0.0/4"         // Reserved / broadcast
    };

    private static final String[] BLOCKED_IPV6_CIDRS = {
            "::/96",              // Unspecified + ::1 + IPv4-compatible IPv6 (::a.b.c.d)
            "64:ff9b::/96",       // NAT64 well-known prefix (RFC 6052)
            "64:ff9b:1::/48",     // NAT64 local-use prefix (RFC 8215)
            "100::/64",           // Discard-Only (RFC 6666)
            "2001::/32",          // Teredo tunneling
            "2001:db8::/32",      // Documentation
            "2002::/16",          // 6to4 tunneling (embeds IPv4 in bytes 2..5)
            "fc00::/7",           // Unique local address (ULA)
            "fe80::/10",          // Link-local
            "fec0::/10",          // Deprecated site-local
            "ff00::/8"            // Multicast
    };

    private final boolean blockPrivateUrls;
    private final List<String> allowedEntries;
    private final HostResolver resolver;
    private final long dnsTimeoutMs;

    @Autowired
    public OutboundUrlGuard(ConsoleProperties properties) {
        this(properties == null || properties.getSecurity() == null
                        || properties.getSecurity().isBlockPrivateWebhookUrls(),
                properties == null || properties.getSecurity() == null
                        ? List.of()
                        : properties.getSecurity().getWebhookAllowedCidrs(),
                InetAddress::getAllByName,
                DEFAULT_DNS_TIMEOUT_MS);
    }

    public OutboundUrlGuard(boolean blockPrivateUrls, List<String> allowedEntries, HostResolver resolver) {
        this(blockPrivateUrls, allowedEntries, resolver, DEFAULT_DNS_TIMEOUT_MS);
    }

    public OutboundUrlGuard(boolean blockPrivateUrls, List<String> allowedEntries,
                            HostResolver resolver, long dnsTimeoutMs) {
        this.blockPrivateUrls = blockPrivateUrls;
        this.allowedEntries = allowedEntries == null ? List.of() : List.copyOf(allowedEntries);
        this.resolver = resolver == null ? InetAddress::getAllByName : resolver;
        this.dnsTimeoutMs = Math.max(50L, dnsTimeoutMs);
    }

    /** 默认严格实例（阻止私网与云元数据访问）。 */
    public static OutboundUrlGuard strict() {
        return new OutboundUrlGuard(true, List.of(), InetAddress::getAllByName, DEFAULT_DNS_TIMEOUT_MS);
    }

    /** 宽松实例（仅用于本地环回单测或 dev/E2E 环境显式关闭私网拦截时）。 */
    public static OutboundUrlGuard permissive() {
        return new OutboundUrlGuard(false, List.of(), InetAddress::getAllByName, DEFAULT_DNS_TIMEOUT_MS);
    }

    public boolean isBlockingPrivateUrls() {
        return blockPrivateUrls;
    }

    /** 注册/更新渠道时校验并规范化 URL。 */
    public String validateForRegistration(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        URI uri = parseBasicHttpUri(trimmed);
        if (!blockPrivateUrls) {
            return trimmed;
        }
        String host = normalizeHost(uri.getHost());
        boolean hostWhitelisted = matchesAllowedHost(host);
        if (!hostWhitelisted) {
            checkForbiddenHostname(host);
        }
        if (looksLikeIpLiteral(host)) {
            resolveAndValidateAll(host, true, hostWhitelisted);
        } else {
            // 注册期执行带超时的 DNS 预检；离线/私有环境域名暂不可解析时交由发包期 fail-closed 拦截
            resolveAndValidateAll(host, false, hostWhitelisted);
        }
        return trimmed;
    }

    /**
     * 发包前单次解析并校验全部 A/AAAA 记录，返回固定 IP 目标（fail-closed）。
     * 调用方必须直连 {@link VerifiedTarget#pinnedAddress()}，杜绝二次 DNS 解析。
     */
    public VerifiedTarget resolveForDelivery(String rawUrl) {
        String trimmed = rawUrl == null ? "" : rawUrl.trim();
        URI uri = parseBasicHttpUri(trimmed);
        String scheme = uri.getScheme().toLowerCase(Locale.ROOT);
        String normalizedHost = normalizeHost(uri.getHost());
        int port = resolvePort(uri, scheme);
        String pathAndQuery = buildPathAndQuery(uri);

        List<InetAddress> validated;
        if (!blockPrivateUrls) {
            validated = resolveAllWithTimeout(normalizedHost, true);
        } else {
            boolean hostWhitelisted = matchesAllowedHost(normalizedHost);
            if (!hostWhitelisted) {
                checkForbiddenHostname(normalizedHost);
            }
            validated = resolveAndValidateAll(normalizedHost, true, hostWhitelisted);
        }
        InetAddress pinned = selectPreferredAddress(validated);
        return new VerifiedTarget(scheme, normalizedHost, port, pathAndQuery, pinned, validated);
    }

    /** 兼容便捷校验入口：返回原 URI，内部已执行完整 fail-closed 解析校验。 */
    public URI verifyForDelivery(String rawUrl) {
        resolveForDelivery(rawUrl);
        return parseBasicHttpUri(rawUrl == null ? "" : rawUrl.trim());
    }

    private URI parseBasicHttpUri(String trimmed) {
        try {
            URI uri = URI.create(trimmed);
            String scheme = uri.getScheme();
            if (scheme == null || (!"http".equalsIgnoreCase(scheme) && !"https".equalsIgnoreCase(scheme))
                    || uri.getHost() == null || uri.getHost().isBlank()) {
                throw BusinessException.badRequest("Webhook URL 必须为合法的 http/https 地址");
            }
            if (uri.getRawUserInfo() != null && !uri.getRawUserInfo().isBlank()) {
                throw BusinessException.badRequest("Webhook URL 不允许包含内嵌认证凭据");
            }
            if (uri.getPort() != -1 && (uri.getPort() < 1 || uri.getPort() > 65535)) {
                throw BusinessException.badRequest("Webhook URL 端口范围非法");
            }
            String host = uri.getHost();
            if (containsControlChars(host) || containsControlChars(trimmed)) {
                throw BusinessException.badRequest("Webhook URL 包含非法控制字符");
            }
            return uri;
        } catch (IllegalArgumentException ex) {
            throw BusinessException.badRequest("Webhook URL 格式非法：" + ex.getMessage());
        }
    }

    private static int resolvePort(URI uri, String scheme) {
        if (uri.getPort() > 0) {
            return uri.getPort();
        }
        return "https".equalsIgnoreCase(scheme) ? 443 : 80;
    }

    private static String buildPathAndQuery(URI uri) {
        String path = uri.getRawPath();
        if (path == null || path.isEmpty()) {
            path = "/";
        }
        String query = uri.getRawQuery();
        String combined = query == null ? path : path + "?" + query;
        if (containsControlChars(combined)) {
            throw BusinessException.badRequest("Webhook URL 路径包含非法控制字符");
        }
        return combined;
    }

    private void checkForbiddenHostname(String normalizedHost) {
        String lower = normalizedHost.toLowerCase(Locale.ROOT);
        if (lower.isEmpty()
                || FORBIDDEN_HOSTS.contains(lower)
                || lower.endsWith(".localhost")
                || lower.endsWith(".localdomain")
                || lower.endsWith(".internal")) {
            throw BusinessException.badRequest("Webhook URL 指向受限本地或元数据地址，已被安全策略拦截");
        }
    }

    private List<InetAddress> resolveAndValidateAll(String normalizedHost,
                                                    boolean failOnResolutionError,
                                                    boolean hostWhitelisted) {
        List<InetAddress> addresses = resolveAllWithTimeout(normalizedHost, failOnResolutionError);
        if (addresses.isEmpty()) {
            return List.of();
        }
        for (InetAddress address : addresses) {
            boolean ipWhitelisted = isAllowedByIpWhitelist(address);
            // 云元数据段绝不允许被“主机名白名单”隐式放行，除非明确写入 IP/CIDR 白名单
            if (isCloudMetadataAddress(address) && !ipWhitelisted) {
                throw BusinessException.badRequest("Webhook URL 解析到云元数据地址，已被安全策略拦截");
            }
            if (!ipWhitelisted && !hostWhitelisted && isBlockedAddress(address)) {
                throw BusinessException.badRequest("Webhook URL 解析到环回、内网或保留地址，已被安全策略拦截");
            }
        }
        return addresses;
    }

    private List<InetAddress> resolveAllWithTimeout(String host, boolean failOnError) {
        Future<InetAddress[]> future;
        try {
            future = DNS_EXECUTOR.submit(() -> resolver.resolveAll(host));
        } catch (RejectedExecutionException ex) {
            if (failOnError) {
                throw BusinessException.badRequest("Webhook DNS 解析队列繁忙，拒绝出站请求");
            }
            return List.of();
        }
        InetAddress[] addresses;
        try {
            addresses = future.get(dnsTimeoutMs, TimeUnit.MILLISECONDS);
        } catch (TimeoutException ex) {
            future.cancel(true);
            if (failOnError) {
                throw BusinessException.badRequest("Webhook 目标主机 DNS 解析超时，拒绝出站请求");
            }
            return List.of();
        } catch (InterruptedException ex) {
            future.cancel(true);
            Thread.currentThread().interrupt();
            if (failOnError) {
                throw BusinessException.badRequest("Webhook 目标主机 DNS 解析被中断，拒绝出站请求");
            }
            return List.of();
        } catch (ExecutionException ex) {
            if (failOnError) {
                throw BusinessException.badRequest("Webhook 目标主机无法解析，拒绝出站请求");
            }
            return List.of();
        }
        if (addresses == null || addresses.length == 0) {
            if (failOnError) {
                throw BusinessException.badRequest("Webhook 目标主机未返回有效地址，拒绝出站请求");
            }
            return List.of();
        }
        List<InetAddress> nonNull = Arrays.stream(addresses).filter(java.util.Objects::nonNull).toList();
        if (nonNull.isEmpty() && failOnError) {
            throw BusinessException.badRequest("Webhook 目标主机未返回有效地址，拒绝出站请求");
        }
        return nonNull;
    }

    /** 全部记录均已通过安全检验后，优先选择 IPv4 以提高容器双栈环境连通性，若仅 AAAA 则直连 IPv6。 */
    private static InetAddress selectPreferredAddress(List<InetAddress> validated) {
        for (InetAddress address : validated) {
            if (address instanceof Inet4Address) {
                return address;
            }
        }
        return validated.get(0);
    }

    private boolean matchesAllowedHost(String normalizedHost) {
        for (String rule : allowedEntries) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String trimmed = normalizeHost(rule.trim());
            if (!trimmed.contains("/") && !looksLikeIpLiteral(trimmed) && trimmed.equalsIgnoreCase(normalizedHost)) {
                return true;
            }
        }
        return false;
    }

    private boolean isAllowedByIpWhitelist(InetAddress address) {
        if (allowedEntries.isEmpty()) {
            return false;
        }
        byte[] target = unwrapMappedIpv4(address.getAddress());
        String hostIp = address.getHostAddress();
        for (String rule : allowedEntries) {
            if (rule == null || rule.isBlank()) {
                continue;
            }
            String trimmed = rule.trim();
            if (trimmed.equalsIgnoreCase(hostIp)) {
                return true;
            }
            if (trimmed.contains("/") && matchesCidr(target, trimmed)) {
                return true;
            }
            if (!trimmed.contains("/") && looksLikeIpLiteral(stripBrackets(trimmed))) {
                try {
                    byte[] allowedBytes = unwrapMappedIpv4(
                            InetAddress.getByName(stripBrackets(trimmed)).getAddress());
                    if (Arrays.equals(target, allowedBytes)) {
                        return true;
                    }
                } catch (UnknownHostException ignored) {
                    // 忽略无效规则项
                }
            }
        }
        return false;
    }

    static boolean isCloudMetadataAddress(InetAddress address) {
        byte[] raw = unwrapMappedIpv4(address.getAddress());
        if (raw.length == 4) {
            for (String cidr : CLOUD_METADATA_CIDRS) {
                if (matchesCidr(raw, cidr)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.length == 16) {
            // 防止 NAT64 / 6to4 / IPv4-compatible IPv6 隧道在主机名白名单下绕过云元数据拦截
            return matchesCidr(raw, "::/96")
                    || matchesCidr(raw, "64:ff9b::/96")
                    || matchesCidr(raw, "64:ff9b:1::/48")
                    || matchesCidr(raw, "2002::/16")
                    || matchesCidr(raw, "2001::/32");
        }
        return false;
    }

    static boolean isBlockedAddress(InetAddress address) {
        if (address.isAnyLocalAddress()
                || address.isLoopbackAddress()
                || address.isLinkLocalAddress()
                || address.isSiteLocalAddress()
                || address.isMulticastAddress()) {
            return true;
        }
        byte[] raw = unwrapMappedIpv4(address.getAddress());
        if (raw.length == 4) {
            for (String cidr : BLOCKED_IPV4_CIDRS) {
                if (matchesCidr(raw, cidr)) {
                    return true;
                }
            }
            return false;
        }
        if (raw.length == 16) {
            for (String cidr : BLOCKED_IPV6_CIDRS) {
                if (matchesCidr(raw, cidr)) {
                    return true;
                }
            }
        }
        return false;
    }

    /** 将 IPv4-mapped IPv6（::ffff:a.b.c.d）还原为 4 字节 IPv4，防止通过 IPv6 映射绕过 IPv4 CIDR 拦截。 */
    private static byte[] unwrapMappedIpv4(byte[] bytes) {
        if (bytes.length == 16) {
            boolean mapped = true;
            for (int i = 0; i < 10; i++) {
                if (bytes[i] != 0) {
                    mapped = false;
                    break;
                }
            }
            if (mapped && bytes[10] == (byte) 0xFF && bytes[11] == (byte) 0xFF) {
                return new byte[]{bytes[12], bytes[13], bytes[14], bytes[15]};
            }
        }
        return bytes;
    }

    private static boolean matchesCidr(byte[] target, String cidr) {
        int slash = cidr.indexOf('/');
        if (slash <= 0 || slash == cidr.length() - 1) {
            return false;
        }
        try {
            int prefixLen = Integer.parseInt(cidr.substring(slash + 1).trim());
            byte[] network = unwrapMappedIpv4(InetAddress.getByName(cidr.substring(0, slash).trim()).getAddress());
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

    public static boolean looksLikeIpLiteral(String host) {
        if (host == null || host.isEmpty()) {
            return false;
        }
        String bare = stripBrackets(host);
        if (bare.contains(":")) {
            return true; // IPv6 字面量
        }
        // IPv4 点分或纯数字/十六进制混淆字面量（如 127.0.0.1 / 2130706433 / 0x7f000001）
        return bare.matches("^[0-9.]+$") || bare.matches("^0[xX][0-9a-fA-F.]+$");
    }

    private static String stripBrackets(String host) {
        if (host.startsWith("[") && host.endsWith("]") && host.length() > 2) {
            return host.substring(1, host.length() - 1);
        }
        return host;
    }

    /** 去除方括号与 FQDN 尾部点号（如 "localhost."），防止尾部点号绕过主机名黑名单。 */
    private static String normalizeHost(String host) {
        String bare = stripBrackets(host);
        int end = bare.length();
        while (end > 0 && bare.charAt(end - 1) == '.') {
            end--;
        }
        return bare.substring(0, end);
    }

    private static boolean containsControlChars(String value) {
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            if (c <= 0x1F || c == 0x7F) {
                return true;
            }
        }
        return false;
    }

    static boolean isIpv4OrIpv6(InetAddress address) {
        return address instanceof Inet4Address || address instanceof Inet6Address;
    }
}
