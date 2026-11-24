package io.tieringkv.webconsole.resp;

import javax.net.ssl.SNIHostName;
import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.TrustManagerFactory;
import java.io.BufferedInputStream;
import java.io.BufferedOutputStream;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyStore;
import java.security.cert.Certificate;
import java.security.cert.CertificateFactory;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.regex.Pattern;

/**
 * 单条 TCP/TLS 连接的 RESP 客户端（非线程安全，由连接池管理）。
 *
 * <p>O2-A 支持单次刷盘多命令管线批处理（{@link #executeBatch}）；
 * O2-B 支持可选 TLS 加密、SNI、主机名校验与自定义 PEM CA 信任库。
 */
public final class RespClient implements AutoCloseable {

    private static final Pattern IPV4_PATTERN = Pattern.compile("^\\d{1,3}(\\.\\d{1,3}){3}$");

    /** 数据面连接 TLS 选项。 */
    public record TlsOptions(boolean enabled, boolean verifyHostname, String caCertPem) {
        public static TlsOptions disabled() {
            return new TlsOptions(false, true, null);
        }
    }

    private final Socket socket;
    private final InputStream in;
    private final OutputStream out;
    private volatile boolean broken;

    private RespClient(Socket socket) throws IOException {
        this.socket = socket;
        this.in = new BufferedInputStream(socket.getInputStream(), 16384);
        this.out = new BufferedOutputStream(socket.getOutputStream(), 16384);
    }

    public static RespClient open(String host, int port, int connectTimeoutMs,
                                  int readTimeoutMs) throws IOException {
        return open(host, port, connectTimeoutMs, readTimeoutMs, TlsOptions.disabled());
    }

    public static RespClient open(String host, int port, int connectTimeoutMs,
                                  int readTimeoutMs, TlsOptions tls) throws IOException {
        Socket raw = new Socket();
        raw.setTcpNoDelay(true);
        raw.setKeepAlive(true);
        raw.connect(new InetSocketAddress(host, port), connectTimeoutMs);
        raw.setSoTimeout(readTimeoutMs);
        Socket effective = raw;
        try {
            if (tls != null && tls.enabled()) {
                SSLContext sslContext = buildSslContext(tls.caCertPem());
                SSLSocket sslSocket = (SSLSocket) sslContext.getSocketFactory()
                        .createSocket(raw, host, port, true);
                sslSocket.setSoTimeout(readTimeoutMs);
                SSLParameters params = sslSocket.getSSLParameters();
                if (tls.verifyHostname()) {
                    params.setEndpointIdentificationAlgorithm("HTTPS");
                }
                if (host != null && !host.isBlank() && !isIpLiteral(host)) {
                    params.setServerNames(List.of(new SNIHostName(host)));
                }
                sslSocket.setSSLParameters(params);
                sslSocket.startHandshake();
                effective = sslSocket;
            }
            return new RespClient(effective);
        } catch (IOException | RuntimeException ex) {
            try {
                effective.close();
            } catch (IOException ignored) {
                // 关闭阶段忽略
            }
            if (effective != raw) {
                try {
                    raw.close();
                } catch (IOException ignored) {
                    // 关闭阶段忽略
                }
            }
            throw ex;
        }
    }

    /** 校验自定义 PEM CA 证书语法（供配置保存阶段提前失败）。 */
    public static void validateCaCertPem(String caCertPem) {
        if (caCertPem == null || caCertPem.isBlank()) {
            return;
        }
        try {
            buildSslContext(caCertPem);
        } catch (IOException ex) {
            throw new IllegalArgumentException(ex.getMessage(), ex);
        }
    }

    private static SSLContext buildSslContext(String caCertPem) throws IOException {
        try {
            if (caCertPem == null || caCertPem.isBlank()) {
                return SSLContext.getDefault();
            }
            CertificateFactory cf = CertificateFactory.getInstance("X.509");
            Collection<? extends Certificate> certs = cf.generateCertificates(
                    new ByteArrayInputStream(caCertPem.trim().getBytes(StandardCharsets.UTF_8)));
            if (certs.isEmpty()) {
                throw new IOException("TLS CA 证书 PEM 未包含有效 X.509 证书");
            }
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int idx = 0;
            for (Certificate cert : certs) {
                ks.setCertificateEntry("ca-" + (idx++), cert);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (GeneralSecurityException ex) {
            throw new IOException("解析 TLS CA 证书失败：" + ex.getMessage(), ex);
        }
    }

    private static boolean isIpLiteral(String host) {
        return host.indexOf(':') >= 0 || IPV4_PATTERN.matcher(host).matches();
    }

    /** 便捷编码：把字符串命令参数转为 UTF-8 字节列表供管线批处理使用。 */
    public static List<byte[]> commandBytes(String... args) {
        List<byte[]> encoded = new ArrayList<>(args.length);
        for (String arg : args) {
            encoded.add(arg.getBytes(StandardCharsets.UTF_8));
        }
        return encoded;
    }

    /** 执行命令并返回应答（错误应答也正常返回，由调用方判定）。 */
    public synchronized RespReply execute(List<byte[]> args) throws IOException {
        try {
            out.write(RespCodec.encodeCommand(args));
            out.flush();
            return RespCodec.read(in);
        } catch (IOException | RuntimeException ex) {
            broken = true;
            throw ex;
        }
    }

    public synchronized RespReply execute(String... args) throws IOException {
        try {
            out.write(RespCodec.encodeCommand(args));
            out.flush();
            return RespCodec.read(in);
        } catch (IOException | RuntimeException ex) {
            broken = true;
            throw ex;
        }
    }

    /**
     * RESP Pipeline 批量执行（O2-A）：一次刷盘发送 N 条命令，严格按序读取 N 条应答。
     *
     * <p>任一阶段发生 I/O 或协议解帧异常即标记 {@code broken=true}，由连接池销毁，
     * 杜绝半读残留流污染后续复用请求。
     */
    public synchronized List<RespReply> executeBatch(List<List<byte[]>> commands) throws IOException {
        if (commands == null || commands.isEmpty()) {
            return List.of();
        }
        try {
            for (List<byte[]> cmd : commands) {
                out.write(RespCodec.encodeCommand(cmd));
            }
            out.flush();
            List<RespReply> replies = new ArrayList<>(commands.size());
            for (int i = 0; i < commands.size(); i++) {
                replies.add(RespCodec.read(in));
            }
            return replies;
        } catch (IOException | RuntimeException ex) {
            broken = true;
            throw ex;
        }
    }

    public boolean isUsable() {
        return !broken && socket.isConnected() && !socket.isClosed()
                && !socket.isInputShutdown() && !socket.isOutputShutdown();
    }

    @Override
    public void close() {
        try {
            socket.close();
        } catch (IOException ignored) {
            // 关闭阶段忽略
        }
    }
}
