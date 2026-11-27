package io.tieringkv.webconsole.security;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.util.List;
import java.util.Map;

/**
 * IP 固定（IP-Pinned）HTTP/1.1 + TLS Webhook 客户端（Wave 1 S1 专项加固）。
 *
 * <p>为什么不直接把域名 URI 交给 {@code java.net.http.HttpClient}：
 * JDK 内置 {@code HttpClient} 在建连前会重新执行一次 DNS 解析，无法绑定外部预校验的
 * {@link java.net.InetAddress}，在 TTL=0 的恶意 DNS 场景下仍存在 DNS Rebinding TOCTOU 窗口。
 *
 * <p>本客户端直接把底层 TCP {@link Socket} 连接到 {@link OutboundUrlGuard.VerifiedTarget#pinnedAddress()}
 * （绝不触发第二次 DNS 解析），并在 HTTPS 场景下于同一已连接套接字上叠加 {@link SSLSocket}，
 * 显式设置 RFC 6066 TLS SNI、{@code HTTPS} 证书主机名校验与原始 HTTP {@code Host} 请求头；
 * 同时严格禁止 3xx 重定向并限制响应行读取上限，防止响应体放大攻击。
 */
public class PinnedWebhookHttpClient {

    private static final int MAX_STATUS_LINE_BYTES = 4096;

    private final SSLContext sslContext;

    public PinnedWebhookHttpClient() {
        this(defaultSslContext());
    }

    public PinnedWebhookHttpClient(SSLContext sslContext) {
        this.sslContext = sslContext == null ? defaultSslContext() : sslContext;
    }

    /** 向已固定 IP 的目标发送一次 HTTP/1.1 POST，并返回响应状态码（绝不跟随 3xx 重定向）。 */
    public int postJson(OutboundUrlGuard.VerifiedTarget target,
                        byte[] bodyBytes,
                        Map<String, String> extraHeaders,
                        Duration timeout) throws IOException {
        int timeoutMs = (int) Math.max(100L, Math.min(Integer.MAX_VALUE, timeout.toMillis()));
        try (Socket socket = openPinnedSocket(target, timeoutMs)) {
            BufferedOutputStream out = new BufferedOutputStream(socket.getOutputStream());
            StringBuilder headerBlock = new StringBuilder(256);
            headerBlock.append("POST ").append(target.pathAndQuery()).append(" HTTP/1.1\r\n");
            headerBlock.append("Host: ").append(target.hostHeaderValue()).append("\r\n");
            headerBlock.append("Content-Type: application/json; charset=UTF-8\r\n");
            headerBlock.append("Content-Length: ").append(bodyBytes.length).append("\r\n");
            headerBlock.append("User-Agent: TieringKV-Console-Webhook/1.0\r\n");
            headerBlock.append("Connection: close\r\n");
            if (extraHeaders != null) {
                for (Map.Entry<String, String> entry : extraHeaders.entrySet()) {
                    String k = sanitizeHeaderPart(entry.getKey());
                    String v = sanitizeHeaderPart(entry.getValue());
                    if (!k.isEmpty()) {
                        headerBlock.append(k).append(": ").append(v).append("\r\n");
                    }
                }
            }
            headerBlock.append("\r\n");
            out.write(headerBlock.toString().getBytes(StandardCharsets.ISO_8859_1));
            out.write(bodyBytes);
            out.flush();

            return readStatusCode(new BufferedInputStream(socket.getInputStream()));
        }
    }

    private Socket openPinnedSocket(OutboundUrlGuard.VerifiedTarget target, int timeoutMs) throws IOException {
        Socket rawSocket = new Socket();
        try {
            // 直接绑定已校验的 InetAddress，JVM 不会再做任何 DNS 查询
            rawSocket.connect(new InetSocketAddress(target.pinnedAddress(), target.port()), timeoutMs);
            rawSocket.setSoTimeout(timeoutMs);
            if (!"https".equalsIgnoreCase(target.scheme())) {
                return rawSocket;
            }
            SSLSocketFactory factory = sslContext.getSocketFactory();
            SSLSocket sslSocket = (SSLSocket) factory.createSocket(
                    rawSocket, target.originalHost(), target.port(), true);
            sslSocket.setSoTimeout(timeoutMs);
            SSLParameters params = sslSocket.getSSLParameters();
            // 严格开启 RFC 2818 HTTPS 证书 SAN/CN 主机名校验，与原始域名比对
            params.setEndpointIdentificationAlgorithm("HTTPS");
            // 非字面量 IP 显式发送 TLS SNI，确保 CDN/反代多域名证书正确路由
            if (!OutboundUrlGuard.looksLikeIpLiteral(target.originalHost())) {
                params.setServerNames(List.of(new SNIHostName(target.originalHost())));
            }
            sslSocket.setSSLParameters(params);
            sslSocket.startHandshake();
            return sslSocket;
        } catch (IOException | RuntimeException ex) {
            try {
                rawSocket.close();
            } catch (IOException suppressed) {
                ex.addSuppressed(suppressed);
            }
            throw ex;
        }
    }

    private static int readStatusCode(InputStream in) throws IOException {
        ByteArrayOutputStream lineBuf = new ByteArrayOutputStream(64);
        int b;
        while ((b = in.read()) != -1) {
            if (b == '\n') {
                break;
            }
            if (b != '\r') {
                if (lineBuf.size() >= MAX_STATUS_LINE_BYTES) {
                    throw new IOException("HTTP 响应状态行过长");
                }
                lineBuf.write(b);
            }
        }
        String statusLine = lineBuf.toString(StandardCharsets.ISO_8859_1).trim();
        if (!statusLine.startsWith("HTTP/")) {
            throw new IOException("对端返回非法 HTTP 响应");
        }
        int firstSpace = statusLine.indexOf(' ');
        if (firstSpace <= 0 || firstSpace + 4 > statusLine.length()) {
            throw new IOException("对端响应缺少状态码");
        }
        int secondSpace = statusLine.indexOf(' ', firstSpace + 1);
        String codeText = secondSpace > 0
                ? statusLine.substring(firstSpace + 1, secondSpace)
                : statusLine.substring(firstSpace + 1);
        try {
            int code = Integer.parseInt(codeText.trim());
            if (code < 100 || code > 599) {
                throw new IOException("对端返回越界 HTTP 状态码");
            }
            return code;
        } catch (NumberFormatException ex) {
            throw new IOException("对端响应状态码格式非法", ex);
        }
    }

    private static String sanitizeHeaderPart(String raw) {
        if (raw == null) {
            return "";
        }
        String cleaned = raw.trim();
        for (int i = 0; i < cleaned.length(); i++) {
            char c = cleaned.charAt(i);
            if (c == '\r' || c == '\n') {
                throw new IllegalArgumentException("HTTP 请求头包含非法换行符");
            }
        }
        return cleaned;
    }

    private static SSLContext defaultSslContext() {
        try {
            return SSLContext.getDefault();
        } catch (NoSuchAlgorithmException ex) {
            throw new IllegalStateException("默认 SSLContext 不可用", ex);
        }
    }
}
